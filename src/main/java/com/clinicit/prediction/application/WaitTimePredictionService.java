package com.clinicit.prediction.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.prediction.api.WaitEstimatesResponse;
import com.clinicit.prediction.application.WaitTimeModelClient.ModelResponse;
import com.clinicit.prediction.application.WaitTimeModelClient.ModelUnavailableException;
import com.clinicit.prediction.application.WaitTimeModelClient.Prediction;
import com.clinicit.prediction.domain.WaitTimeEstimate;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueEntryRepository;
import com.clinicit.queue.domain.QueueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estimated waits for patients in the queue. Advisory only: nothing here changes the queue,
 * and no queue or appointment operation calls it, so the ML service can never slow down or
 * break check-in, calling or consultations.
 *
 * <p>For each waiting patient: features as of now ({@link WaitTimeFeatureBuilder}), one
 * batched call to the ML service with a short timeout, validation of the answer, and the
 * deterministic {@link BaselineWaitTime} whenever the service is off, failing, slow, unsure
 * or returns something implausible.
 *
 * <p><b>Load on the ML service.</b> Estimates are cached per patient and reused while the
 * doctor's queue is unchanged (same latest history event) and younger than
 * {@code cacheTtl}. A board refresh after a queue change costs one batched call per doctor,
 * however many screens and status pages ask. After a failure the service is left alone for
 * {@code failureBackoff}.
 */
@Service
public class WaitTimePredictionService {

    private static final Logger log = LoggerFactory.getLogger(WaitTimePredictionService.class);
    private static final double MAX_PLAUSIBLE_MINUTES = 24 * 60;
    private static final int MAX_CACHED = 5_000;

    private final QueueEntryRepository entries;
    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;
    private final WaitTimeFeatureBuilder featureBuilder;
    private final WaitTimeModelClient model;
    private final PredictionProperties properties;
    private final JdbcTemplate jdbc;

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private volatile Instant modelRetryAfter = Instant.MIN;

    private record Cached(long queueVersion, Instant expiresAt, WaitTimeEstimate estimate) {}

    public WaitTimePredictionService(
            QueueEntryRepository entries,
            DoctorProfileRepository doctors,
            ClinicTime clinicTime,
            WaitTimeFeatureBuilder featureBuilder,
            WaitTimeModelClient model,
            PredictionProperties properties,
            JdbcTemplate jdbc
    ) {
        this.entries = entries;
        this.doctors = doctors;
        this.clinicTime = clinicTime;
        this.featureBuilder = featureBuilder;
        this.model = model;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** Estimates for everyone waiting for one doctor today. Doctors may only ask about themselves. */
    public WaitEstimatesResponse forDoctorToday(Actor actor, UUID requestedDoctorId) {
        UUID doctorId = actor.resolveDoctor(requestedDoctorId);
        doctors.findByIdAndClinicId(doctorId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        LocalDate today = clinicTime.today(actor.clinicId());

        List<QueueEntry> waiting = entries.findByDoctorIdAndQueueDateOrderByTokenNumberAsc(doctorId, today).stream()
                .filter(entry -> entry.getStatus() == QueueStatus.WAITING)
                .toList();
        Map<UUID, WaitTimeEstimate> estimates = estimate(actor.clinicId(), doctorId, waiting);

        return new WaitEstimatesResponse(doctorId, today, waiting.stream()
                .map(entry -> WaitEstimatesResponse.Entry.from(entry, estimates.get(entry.getId())))
                .toList());
    }

    /** The estimate for one queue entry, if it is waiting (used by the public status page). */
    public Optional<WaitTimeEstimate> forEntry(QueueEntry entry) {
        if (entry.getStatus() != QueueStatus.WAITING) return Optional.empty();
        List<QueueEntry> waiting = entries.findByDoctorIdAndQueueDateOrderByTokenNumberAsc(
                        entry.getDoctorId(), entry.getQueueDate()).stream()
                .filter(e -> e.getStatus() == QueueStatus.WAITING)
                .toList();
        // Estimating the doctor's whole queue at once keeps it to one call per queue change.
        return Optional.ofNullable(estimate(entry.getClinicId(), entry.getDoctorId(), waiting).get(entry.getId()));
    }

    private Map<UUID, WaitTimeEstimate> estimate(UUID clinicId, UUID doctorId, List<QueueEntry> waiting) {
        Map<UUID, WaitTimeEstimate> result = new ConcurrentHashMap<>();
        if (waiting.isEmpty()) return result;

        Instant now = clinicTime.instant();
        ZoneId zone = clinicTime.zone(clinicId);
        long queueVersion = queueVersion(clinicId, doctorId, now, zone);

        List<QueueEntry> missing = new ArrayList<>();
        for (QueueEntry entry : waiting) {
            Cached cached = cache.get(entry.getId());
            if (cached != null && cached.queueVersion() == queueVersion && cached.expiresAt().isAfter(now)) {
                result.put(entry.getId(), cached.estimate());
            } else {
                missing.add(entry);
            }
        }
        if (missing.isEmpty()) return result;

        List<WaitTimeFeatures> rows = missing.stream()
                .map(entry -> featureBuilder.at(clinicId, doctorId, entry.getId(), entry.getTokenNumber(),
                        now, Long.MAX_VALUE, zone))
                .toList();
        List<WaitTimeEstimate> estimates = predict(rows, now);

        if (cache.size() > MAX_CACHED) cache.clear();
        for (int i = 0; i < missing.size(); i++) {
            UUID id = missing.get(i).getId();
            result.put(id, estimates.get(i));
            cache.put(id, new Cached(queueVersion, expiry(estimates.get(i), now), estimates.get(i)));
        }
        return result;
    }

    /** Forgets cached estimates and any failure backoff, e.g. after deploying a new model. */
    public void reset() {
        cache.clear();
        modelRetryAfter = Instant.MIN;
    }

    /** The ML service's answer when it is usable, otherwise the baseline, row by row. */
    List<WaitTimeEstimate> predict(List<WaitTimeFeatures> rows, Instant now) {
        String unavailable = null;
        ModelResponse response = null;
        if (!properties.mlEnabled()) {
            unavailable = "ML_DISABLED";
        } else if (now.isBefore(modelRetryAfter)) {
            unavailable = "ML_UNAVAILABLE";
        } else {
            try {
                response = model.predict(rows);
                if (!isValid(response, rows.size())) {
                    log.warn("Wait-time model returned an invalid response; using the baseline");
                    unavailable = "INVALID_PREDICTION";
                    response = null;
                }
            } catch (ModelUnavailableException e) {
                // No request or patient data in the log: only what failed.
                log.warn("Wait-time model unavailable ({}); using the baseline for {}", e.getMessage(),
                        properties.failureBackoff());
                modelRetryAfter = now.plus(properties.failureBackoff());
                unavailable = "ML_UNAVAILABLE";
            }
        }

        List<WaitTimeEstimate> estimates = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (response == null) {
                estimates.add(BaselineWaitTime.estimate(rows.get(i), unavailable));
                continue;
            }
            Prediction p = response.predictions().get(i);
            if ("MODEL".equals(p.source())) {
                int estimate = (int) Math.round(p.estimatedWaitMinutes());
                estimates.add(new WaitTimeEstimate(
                        estimate,
                        Math.min((int) Math.floor(p.lowerBoundMinutes()), estimate),
                        Math.max((int) Math.ceil(p.upperBoundMinutes()), estimate),
                        WaitTimeEstimate.Source.MODEL, response.modelVersion(), null));
            } else {
                // The service declined to use its model for this row (e.g. too little history).
                estimates.add(BaselineWaitTime.estimate(rows.get(i), p.reason()));
            }
        }
        return estimates;
    }

    /** A fallback caused by an outage lasts only until the service may be tried again. */
    private Instant expiry(WaitTimeEstimate estimate, Instant now) {
        Instant normal = now.plus(properties.cacheTtl());
        if ("ML_UNAVAILABLE".equals(estimate.reason()) && modelRetryAfter.isBefore(normal)) {
            return modelRetryAfter;
        }
        return normal;
    }

    static boolean isValid(ModelResponse response, int expected) {
        if (response == null || !"1".equals(response.schemaVersion())
                || response.modelVersion() == null || response.modelVersion().isBlank()
                || response.predictions() == null || response.predictions().size() != expected) {
            return false;
        }
        for (Prediction p : response.predictions()) {
            if (p == null || !("MODEL".equals(p.source()) || "BASELINE".equals(p.source()))) return false;
            if ("BASELINE".equals(p.source())) continue;
            if (!plausible(p.lowerBoundMinutes()) || !plausible(p.estimatedWaitMinutes()) || !plausible(p.upperBoundMinutes())
                    || p.lowerBoundMinutes() > p.estimatedWaitMinutes()
                    || p.estimatedWaitMinutes() > p.upperBoundMinutes()) {
                return false;
            }
        }
        return true;
    }

    private static boolean plausible(Double minutes) {
        return minutes != null && Double.isFinite(minutes) && minutes >= 0 && minutes <= MAX_PLAUSIBLE_MINUTES;
    }

    /** Changes whenever anything happens in this doctor's queue today. */
    private long queueVersion(UUID clinicId, UUID doctorId, Instant now, ZoneId zone) {
        Long seq = jdbc.queryForObject("""
                select coalesce(max(seq), 0) from operational_events
                where clinic_id = ? and doctor_id = ? and occurred_at >= ?
                """, Long.class, clinicId, doctorId,
                now.atZone(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime());
        return seq == null ? 0 : seq;
    }
}
