package com.clinicit.noshow.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.noshow.api.NoShowRiskEvaluation;
import com.clinicit.noshow.api.NoShowRiskResponse;
import com.clinicit.noshow.domain.NoShowRisk;
import com.clinicit.noshow.domain.NoShowRiskRule;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advisory no-show flags ({@link NoShowRiskRule}) and their evaluation on the clinic's own
 * history.
 *
 * <p><b>No leakage.</b> Every judgement is made "as of" a moment: only appointments scheduled
 * before it, and only outcomes recorded before it (the {@code ARRIVED} and {@code NO_SHOW}
 * events' times), count. For the front desk that moment is now; in the evaluation it is the
 * start of the appointment's day, when reception would first see the day's list. The outcome
 * being predicted is never part of its own input.
 *
 * <p>An appointment is <i>kept</i> when the patient arrived, and <i>missed</i> when it was
 * marked no-show without the patient ever arriving. Walk-ins are left out (they are here by
 * definition), and so are cancellations and appointments without an outcome yet.
 */
@Service
@Transactional(readOnly = true)
public class NoShowRiskService {

    static final int DEFAULT_EVALUATION_DAYS = 90;
    static final int MAX_EVALUATION_DAYS = 366;
    static final int CLINIC_RATE_DAYS = 180;

    /** Kept / missed flags of an appointment {@code p} as of {@code :asOf}. */
    private static final String OUTCOME_AS_OF = """
            exists (select 1 from operational_events e
                    where e.appointment_id = p.id and e.clinic_id = p.clinic_id
                      and e.event_type = 'ARRIVED' and e.occurred_at < :asOf)                           as kept,
            exists (select 1 from operational_events e
                    where e.appointment_id = p.id and e.clinic_id = p.clinic_id
                      and e.event_type = 'NO_SHOW' and e.previous_status = 'CONFIRMED'
                      and e.occurred_at < :asOf)                                                        as missed
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ClinicTime clinicTime;

    public NoShowRiskService(NamedParameterJdbcTemplate jdbc, ClinicTime clinicTime) {
        this.jdbc = jdbc;
        this.clinicTime = clinicTime;
    }

    /** Flags for a day's booked and confirmed appointments (not walk-ins), as of now. */
    public List<NoShowRiskResponse> forDay(Actor actor, LocalDate requestedDate) {
        UUID clinicId = actor.clinicId();
        LocalDate date = requestedDate != null ? requestedDate : clinicTime.today(clinicId);
        List<UUID> targets = jdbc.queryForList("""
                select id from appointments
                where clinic_id = :clinic and scheduled_at >= :from and scheduled_at < :to
                  and status in ('BOOKED', 'CONFIRMED') and not walk_in
                """, new MapSqlParameterSource("clinic", clinicId)
                        .addValue("from", date.atStartOfDay()).addValue("to", date.plusDays(1).atStartOfDay()),
                UUID.class);
        Instant now = clinicTime.instant();
        LocalDateTime localNow = clinicTime.now(clinicId);
        Double clinicRate = clinicMissRate(clinicId, now, localNow);
        Map<UUID, int[]> history = priorHistory(clinicId, targets, now, localNow);
        return targets.stream().map(id -> {
            int[] counts = history.getOrDefault(id, new int[2]);
            return NoShowRiskResponse.of(id, NoShowRiskRule.assess(counts[0], counts[1], clinicRate));
        }).toList();
    }

    /**
     * Replays the rule over past days (default: the 90 days before today), each day judged as
     * of its own start, and compares the flags with what then happened.
     */
    public NoShowRiskEvaluation evaluate(Actor actor, LocalDate requestedFrom, LocalDate requestedTo) {
        UUID clinicId = actor.clinicId();
        ZoneId zone = clinicTime.zone(clinicId);
        LocalDate to = requestedTo != null ? requestedTo : clinicTime.today(clinicId).minusDays(1);
        LocalDate from = requestedFrom != null ? requestedFrom : to.minusDays(DEFAULT_EVALUATION_DAYS - 1);
        if (from.isAfter(to)) throw new InvalidRequestException("from must not be after to");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_EVALUATION_DAYS) {
            throw new InvalidRequestException("The range can be at most " + MAX_EVALUATION_DAYS + " days");
        }

        long judged = 0, unknown = 0, missed = 0, flagged = 0, flaggedMissed = 0, typical = 0, typicalMissed = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // Appointments of the day whose outcome is known by now: this is the label, not an input.
            Map<UUID, Boolean> outcomes = new HashMap<>();
            jdbc.query("""
                    select p.id, %s
                    from appointments p
                    where p.clinic_id = :clinic and p.scheduled_at >= :from and p.scheduled_at < :to and not p.walk_in
                    """.formatted(OUTCOME_AS_OF), new MapSqlParameterSource("clinic", clinicId)
                            .addValue("from", day.atStartOfDay()).addValue("to", day.plusDays(1).atStartOfDay())
                            .addValue("asOf", Timestamp.from(clinicTime.instant())),
                    rs -> {
                        if (rs.getBoolean("missed")) outcomes.put(rs.getObject("id", UUID.class), true);
                        else if (rs.getBoolean("kept")) outcomes.put(rs.getObject("id", UUID.class), false);
                    });
            if (outcomes.isEmpty()) continue;

            // Judged with what was known at the start of that day.
            Instant asOf = day.atStartOfDay(zone).toInstant();
            LocalDateTime asOfLocal = day.atStartOfDay();
            Double clinicRate = clinicMissRate(clinicId, asOf, asOfLocal);
            Map<UUID, int[]> history = priorHistory(clinicId, new ArrayList<>(outcomes.keySet()), asOf, asOfLocal);
            for (Map.Entry<UUID, Boolean> outcome : outcomes.entrySet()) {
                int[] counts = history.getOrDefault(outcome.getKey(), new int[2]);
                NoShowRisk risk = NoShowRiskRule.assess(counts[0], counts[1], clinicRate);
                boolean wasMissed = outcome.getValue();
                if (wasMissed) missed++;
                switch (risk.level()) {
                    case UNKNOWN -> unknown++;
                    case ELEVATED -> {
                        judged++;
                        flagged++;
                        if (wasMissed) flaggedMissed++;
                    }
                    case TYPICAL -> {
                        judged++;
                        typical++;
                        if (wasMissed) typicalMissed++;
                    }
                }
            }
        }
        return NoShowRiskEvaluation.of(from, to, judged + unknown, missed, unknown, flagged, flaggedMissed,
                typical, typicalMissed);
    }

    /** The clinic's never-arrived rate over the 180 days before the moment, or null without data. */
    private Double clinicMissRate(UUID clinicId, Instant asOf, LocalDateTime asOfLocal) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where missed) as missed, count(*) filter (where kept or missed) as known
                from (
                    select %s
                    from appointments p
                    where p.clinic_id = :clinic and not p.walk_in and p.scheduled_at >= :since and p.scheduled_at < :asOfLocal
                ) outcomes
                """.formatted(OUTCOME_AS_OF), new MapSqlParameterSource("clinic", clinicId)
                        .addValue("since", asOfLocal.minusDays(CLINIC_RATE_DAYS))
                        .addValue("asOfLocal", asOfLocal)
                        .addValue("asOf", Timestamp.from(asOf)));
        long known = ((Number) row.get("known")).longValue();
        return known == 0 ? null : ((Number) row.get("missed")).doubleValue() / known;
    }

    /**
     * For each target appointment: {known outcomes, missed} over the patient's last 10 earlier
     * appointments with an outcome known at the moment (the target itself never counts).
     */
    private Map<UUID, int[]> priorHistory(UUID clinicId, List<UUID> targets, Instant asOf, LocalDateTime asOfLocal) {
        Map<UUID, int[]> result = new HashMap<>();
        if (targets.isEmpty()) return result;
        jdbc.query("""
                select t.id,
                       count(*) filter (where h.kept or h.missed)   as known,
                       count(*) filter (where h.missed)             as missed
                from appointments t
                left join lateral (
                    select kept, missed from (
                        select p.scheduled_at, %s
                        from appointments p
                        where p.clinic_id = t.clinic_id and p.patient_id = t.patient_id and p.id <> t.id
                          and not p.walk_in and p.scheduled_at < :asOfLocal
                    ) prior
                    where kept or missed
                    order by scheduled_at desc
                    limit %d
                ) h on true
                where t.clinic_id = :clinic and t.id in (:targets)
                group by t.id
                """.formatted(OUTCOME_AS_OF, NoShowRiskRule.HISTORY_WINDOW),
                new MapSqlParameterSource("clinic", clinicId).addValue("targets", targets)
                        .addValue("asOf", Timestamp.from(asOf)).addValue("asOfLocal", asOfLocal),
                rs -> {
                    result.put(rs.getObject("id", UUID.class), new int[]{rs.getInt("known"), rs.getInt("missed")});
                });
        return result;
    }
}
