package com.clinicit.analytics.application;

import com.clinicit.analytics.api.DailySummaryResponse;
import com.clinicit.analytics.api.DoctorAnalyticsResponse;
import com.clinicit.analytics.api.NoShowResponse;
import com.clinicit.analytics.api.QueueAnalyticsResponse;
import com.clinicit.analytics.api.WaitTimesResponse;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic operational analytics, computed by PostgreSQL from the immutable
 * operational history ({@code operational_events}), never from the current state of
 * appointments or queue entries. No estimates, no models: every figure is a count,
 * a duration between two recorded events, or a ratio of counts. Definitions are in
 * docs/ANALYTICS.md.
 *
 * <p>Every query is scoped to the caller's clinic. A day is the clinic's local calendar
 * day, turned into an exact [start, end) instant window here, and hours are clinic-local
 * hours ({@code occurred_at at time zone <clinic timezone>}).
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 30;

    /**
     * One row per queue entry that joined the queue in the window: when it joined, was
     * first called, started and completed. Parameters: see {@link Scope#eventParams()}.
     */
    private static final String VISITS = """
            with scoped as (
                select * from operational_events
                where clinic_id = ? and occurred_at >= ? and occurred_at < ? %s
            ),
            visits as (
                select queue_entry_id, appointment_id, doctor_id,
                       min(occurred_at) filter (where event_type = 'WAITING')                as joined_at,
                       min(occurred_at) filter (where event_type = 'CALLED')                 as called_at,
                       min(occurred_at) filter (where event_type = 'IN_CONSULTATION')        as started_at,
                       min(occurred_at) filter (where event_type = 'COMPLETED')              as completed_at
                from scoped
                where queue_entry_id is not null
                group by queue_entry_id, appointment_id, doctor_id
            ),
            timed as (
                select v.*,
                       extract(epoch from called_at - joined_at)       as wait_seconds,
                       extract(epoch from completed_at - started_at)   as consultation_seconds
                from visits v
                where joined_at is not null
            )
            """;

    private final JdbcTemplate jdbc;
    private final ClinicTime clinicTime;
    private final DoctorProfileRepository doctors;

    public AnalyticsService(JdbcTemplate jdbc, ClinicTime clinicTime, DoctorProfileRepository doctors) {
        this.jdbc = jdbc;
        this.clinicTime = clinicTime;
        this.doctors = doctors;
    }

    public DailySummaryResponse summary(Actor actor, LocalDate requestedDate, UUID requestedDoctorId) {
        Scope scope = scope(actor, requestedDate, requestedDoctorId);

        Map<String, Object> visits = jdbc.queryForMap(VISITS.formatted(scope.doctorFilter()) + """
                select count(*)                                                                  as patients,
                       count(completed_at)                                                       as completed,
                       avg(wait_seconds)                                                         as avg_wait,
                       percentile_cont(0.5) within group (order by wait_seconds)                 as median_wait,
                       avg(consultation_seconds)                                                 as avg_consultation,
                       avg(extract(epoch from called_at - (a.scheduled_at at time zone ?)))      as avg_delay
                from timed t
                join appointments a on a.id = t.appointment_id
                """, scope.eventParams(scope.zone().getId()));

        Attendance attendance = attendance(scope, scope.date(), scope.date());

        return new DailySummaryResponse(
                scope.date(),
                scope.zone().getId(),
                scope.doctorId(),
                attendance.scheduled(),
                count(visits.get("patients")),
                count(visits.get("completed")),
                seconds(visits.get("avg_wait")),
                seconds(visits.get("median_wait")),
                seconds(visits.get("avg_consultation")),
                seconds(visits.get("avg_delay")),
                attendance.cancelled(),
                attendance.noShows(),
                attendance.cancellationRate(),
                attendance.noShowRate(),
                queueLengthAtEnd(scope));
    }

    public WaitTimesResponse waitTimes(Actor actor, LocalDate requestedDate, UUID requestedDoctorId) {
        Scope scope = scope(actor, requestedDate, requestedDoctorId);
        String called = VISITS.formatted(scope.doctorFilter());

        Map<String, Object> overall = jdbc.queryForMap(called + """
                select count(*)                                                     as called,
                       avg(wait_seconds)                                            as avg_wait,
                       percentile_cont(0.5) within group (order by wait_seconds)    as median_wait,
                       percentile_cont(0.9) within group (order by wait_seconds)    as p90_wait,
                       max(wait_seconds)                                            as max_wait
                from timed
                where wait_seconds is not null
                """, scope.eventParams());

        List<WaitTimesResponse.Hour> byHour = jdbc.query(called + """
                select extract(hour from called_at at time zone ?)::int              as hour,
                       count(*)                                                      as called,
                       avg(wait_seconds)                                             as avg_wait,
                       percentile_cont(0.5) within group (order by wait_seconds)     as median_wait
                from timed
                where wait_seconds is not null
                group by 1
                order by 1
                """, (rs, i) -> new WaitTimesResponse.Hour(
                        rs.getInt("hour"), rs.getLong("called"),
                        seconds(rs.getObject("avg_wait")), seconds(rs.getObject("median_wait"))),
                scope.eventParams(scope.zone().getId()));

        return new WaitTimesResponse(
                scope.date(),
                scope.doctorId(),
                count(overall.get("called")),
                seconds(overall.get("avg_wait")),
                seconds(overall.get("median_wait")),
                seconds(overall.get("p90_wait")),
                seconds(overall.get("max_wait")),
                byHour);
    }

    /** Every doctor of the clinic (a doctor: only themselves), including those with no patients yet. */
    public DoctorAnalyticsResponse doctors(Actor actor, LocalDate requestedDate) {
        Scope scope = scope(actor, requestedDate, actor.isDoctor() ? actor.doctorProfileId() : null);

        List<Object> params = new ArrayList<>(List.of(scope.eventParams()));
        params.add(actor.clinicId());
        String onlyDoctor = "";
        if (scope.doctorId() != null) {
            onlyDoctor = "and d.id = ?";
            params.add(scope.doctorId());
        }

        List<DoctorAnalyticsResponse.Doctor> rows = jdbc.query(VISITS.formatted(scope.doctorFilter()) + """
                , per_doctor as (
                    select doctor_id,
                           count(called_at)                                  as called,
                           count(completed_at)                               as handled,
                           avg(wait_seconds)                                 as avg_wait,
                           avg(consultation_seconds)                         as avg_consultation,
                           sum(consultation_seconds)                         as consultation_total,
                           extract(epoch from max(completed_at) - min(called_at)) as span_seconds
                    from timed
                    group by doctor_id
                )
                select d.id, d.display_name, p.called, p.handled, p.avg_wait, p.avg_consultation,
                       p.consultation_total, p.span_seconds
                from doctor_profiles d
                left join per_doctor p on p.doctor_id = d.id
                where d.clinic_id = ? %s
                order by d.display_name, d.id
                """.formatted(onlyDoctor), (rs, i) -> {
                    Double consultation = seconds(rs.getObject("consultation_total"));
                    Double span = seconds(rs.getObject("span_seconds"));
                    Double utilization = consultation == null || span == null || span <= 0
                            ? null
                            : Math.min(1.0, consultation / span);
                    return new DoctorAnalyticsResponse.Doctor(
                            rs.getObject("id", UUID.class),
                            rs.getString("display_name"),
                            rs.getLong("called"),
                            rs.getLong("handled"),
                            seconds(rs.getObject("avg_wait")),
                            seconds(rs.getObject("avg_consultation")),
                            consultation,
                            utilization);
                }, params.toArray());

        return new DoctorAnalyticsResponse(scope.date(), rows);
    }

    public QueueAnalyticsResponse queue(Actor actor, LocalDate requestedDate, UUID requestedDoctorId) {
        Scope scope = scope(actor, requestedDate, requestedDoctorId);

        Map<Integer, long[]> perHour = new HashMap<>();
        jdbc.query("""
                select extract(hour from occurred_at at time zone ?)::int                               as hour,
                       count(*) filter (where event_type = 'WAITING')                                   as joined,
                       count(*) filter (where event_type = 'COMPLETED')                                 as completed,
                       sum(%s)                                                                          as queue_delta
                from operational_events
                where clinic_id = ? and occurred_at >= ? and occurred_at < ? %s
                group by 1
                """.formatted(QUEUE_DELTA, scope.doctorFilter()),
                (RowCallbackHandler) rs -> perHour.put(rs.getInt("hour"),
                        new long[] {rs.getLong("joined"), rs.getLong("completed"), rs.getLong("queue_delta")}),
                scope.eventParamsAfter(scope.zone().getId()));

        List<QueueAnalyticsResponse.Hour> hours = new ArrayList<>();
        long waiting = 0;
        for (int hour = 0; hour <= scope.lastHour(); hour++) {
            long[] h = perHour.getOrDefault(hour, new long[3]);
            waiting += h[2];
            hours.add(new QueueAnalyticsResponse.Hour(hour, h[0], h[1], waiting));
        }
        return new QueueAnalyticsResponse(scope.date(), scope.doctorId(), queueLengthAtEnd(scope), hours);
    }

    public NoShowResponse noShows(Actor actor, LocalDate requestedFrom, LocalDate requestedTo, UUID requestedDoctorId) {
        UUID doctorId = scopeDoctor(actor, requestedDoctorId);
        LocalDate today = clinicTime.today(actor.clinicId());
        LocalDate to = requestedTo != null ? requestedTo : today;
        LocalDate from = requestedFrom != null ? requestedFrom : to.minusDays(DEFAULT_RANGE_DAYS - 1);
        if (from.isAfter(to)) {
            throw new InvalidRequestException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new InvalidRequestException("The range can be at most " + MAX_RANGE_DAYS + " days");
        }
        Scope scope = new Scope(actor.clinicId(), doctorId, from, clinicTime.zone(actor.clinicId()),
                clinicTime.now(actor.clinicId()));

        Attendance total = attendance(scope, from, to);

        List<Object> params = new ArrayList<>(List.of(actor.clinicId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (doctorId != null) params.add(doctorId);
        List<NoShowResponse.Day> byDay = jdbc.query(ATTENDANCE.formatted(doctorId == null ? "" : "and a.doctor_id = ?") + """
                select scheduled_at::date as day, count(*) as scheduled,
                       count(*) filter (where cancelled) as cancelled,
                       count(*) filter (where no_show_from is not null) as no_shows
                from attendance
                group by 1
                order by 1
                """, (rs, i) -> new NoShowResponse.Day(
                        rs.getObject("day", LocalDate.class), rs.getLong("scheduled"),
                        rs.getLong("cancelled"), rs.getLong("no_shows")),
                params.toArray());

        return new NoShowResponse(from, to, doctorId,
                total.scheduled(), total.cancelled(), total.noShows(),
                total.cancellationRate(), total.noShowRate(),
                total.neverArrived(), total.leftBeforeQueue(), total.leftQueue(),
                byDay);
    }

    /**
     * +1 when a patient starts waiting (joins, or returns after a skip); −1 when they stop
     * waiting (called, or skipped while waiting). The running sum is the queue length.
     */
    private static final String QUEUE_DELTA = """
            case when event_type in ('WAITING', 'REQUEUED') then 1
                 when previous_status = 'WAITING' then -1
                 else 0 end""";

    private long queueLengthAtEnd(Scope scope) {
        Long length = jdbc.queryForObject("""
                select coalesce(sum(%s), 0)
                from operational_events
                where clinic_id = ? and occurred_at >= ? and occurred_at < ? %s
                """.formatted(QUEUE_DELTA, scope.doctorFilter()), Long.class, scope.eventParams());
        return length == null ? 0 : length;
    }

    /**
     * Appointments scheduled in the range and how each ended, from the history: cancelled,
     * or a no-show (and from which state). Walk-ins count as scheduled for their day.
     */
    private static final String ATTENDANCE = """
            with attendance as (
                select a.id, a.scheduled_at,
                       exists (select 1 from operational_events e
                               where e.appointment_id = a.id and e.clinic_id = a.clinic_id
                                 and e.event_type = 'CANCELLED')                            as cancelled,
                       (select e.previous_status from operational_events e
                        where e.appointment_id = a.id and e.clinic_id = a.clinic_id
                          and e.event_type = 'NO_SHOW' limit 1)                             as no_show_from
                from appointments a
                where a.clinic_id = ? and a.scheduled_at >= ? and a.scheduled_at < ? %s
            )
            """;

    private Attendance attendance(Scope scope, LocalDate from, LocalDate to) {
        List<Object> params = new ArrayList<>(List.of(scope.clinicId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (scope.doctorId() != null) params.add(scope.doctorId());
        return jdbc.queryForObject(ATTENDANCE.formatted(scope.doctorId() == null ? "" : "and a.doctor_id = ?") + """
                select count(*)                                                    as scheduled,
                       count(*) filter (where cancelled)                           as cancelled,
                       count(*) filter (where no_show_from is not null)            as no_shows,
                       count(*) filter (where no_show_from = 'CONFIRMED')          as never_arrived,
                       count(*) filter (where no_show_from = 'ARRIVED')            as left_before_queue,
                       count(*) filter (where no_show_from = 'SKIPPED')            as left_queue
                from attendance
                """, (rs, i) -> new Attendance(
                        rs.getLong("scheduled"), rs.getLong("cancelled"), rs.getLong("no_shows"),
                        rs.getLong("never_arrived"), rs.getLong("left_before_queue"), rs.getLong("left_queue")),
                params.toArray());
    }

    private record Attendance(
            long scheduled, long cancelled, long noShows, long neverArrived, long leftBeforeQueue, long leftQueue
    ) {
        Double cancellationRate() {
            return scheduled == 0 ? null : (double) cancelled / scheduled;
        }

        /** Of the appointments still expected (not cancelled), the share whose patient did not come. */
        Double noShowRate() {
            long expected = scheduled - cancelled;
            return expected == 0 ? null : (double) noShows / expected;
        }
    }

    private Scope scope(Actor actor, LocalDate requestedDate, UUID requestedDoctorId) {
        UUID doctorId = scopeDoctor(actor, requestedDoctorId);
        LocalDateTime now = clinicTime.now(actor.clinicId());
        return new Scope(actor.clinicId(), doctorId, requestedDate != null ? requestedDate : now.toLocalDate(),
                clinicTime.zone(actor.clinicId()), now);
    }

    /** Front desk: the whole clinic or one of its doctors. A doctor: always and only themselves. */
    private UUID scopeDoctor(Actor actor, UUID requestedDoctorId) {
        UUID doctorId = actor.isDoctor() ? actor.resolveDoctor(requestedDoctorId) : requestedDoctorId;
        if (doctorId != null && doctors.findByIdAndClinicId(doctorId, actor.clinicId()).isEmpty()) {
            throw new NotFoundException("Doctor not found");
        }
        return doctorId;
    }

    /** One clinic (and optionally one doctor) over one clinic-local day. */
    record Scope(UUID clinicId, UUID doctorId, LocalDate date, ZoneId zone, LocalDateTime now) {

        OffsetDateTime start() {
            return date.atStartOfDay(zone).toOffsetDateTime();
        }

        OffsetDateTime end() {
            return date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }

        String doctorFilter() {
            return doctorId == null ? "" : "and doctor_id = ?";
        }

        /** The window parameters, then any extra parameters used after it. */
        Object[] eventParams(Object... after) {
            List<Object> params = new ArrayList<>(List.of(clinicId, start(), end()));
            if (doctorId != null) params.add(doctorId);
            params.addAll(List.of(after));
            return params.toArray();
        }

        /** Parameters used before the window, then the window. */
        Object[] eventParamsAfter(Object... before) {
            List<Object> params = new ArrayList<>(List.of(before));
            params.addAll(List.of(eventParams()));
            return params.toArray();
        }

        /** Last clinic-local hour with data so far: 23 for a past day, the current hour today, none for the future. */
        int lastHour() {
            LocalDate today = now.toLocalDate();
            if (date.isBefore(today)) return 23;
            if (date.isAfter(today)) return -1;
            return now.getHour();
        }
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Double seconds(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
