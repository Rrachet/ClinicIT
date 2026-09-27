package com.clinicit.prediction.application;

import com.clinicit.prediction.domain.WaitTimeFeatures;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Computes a waiting patient's features <em>as of</em> one moment, from the immutable
 * operational history alone. This is the only definition of the features: live predictions
 * call it with "now", and the training dataset calls it with each historical moment, so the
 * model is trained on exactly what it will be given.
 *
 * <p><b>No future information.</b> Every event the query reads is filtered by
 * {@code occurred_at <= asOf} and {@code seq <= upToSeq}. The latter pins a historical
 * snapshot to the state right after one specific event, even when several events share a
 * timestamp. Nothing is read from the current state of appointments or queue entries, which
 * would reflect what happened later. A test adds later events and checks that the features
 * of an earlier moment do not change.
 */
@Component
public class WaitTimeFeatureBuilder {

    /** Days of history for the doctor's usual consultation length and wait. */
    static final int HISTORY_DAYS = 28;

    private static final String QUERY = """
            with ev as (
                select * from operational_events
                where clinic_id = :clinic and doctor_id = :doctor
                  and occurred_at >= :historyStart and occurred_at <= :asOf and seq <= :upToSeq
            ),
            today as (
                select * from ev where occurred_at >= :dayStart
            ),
            entry_state as (
                select distinct on (queue_entry_id) queue_entry_id, event_type, token_number
                from today
                where queue_entry_id is not null
                order by queue_entry_id, seq desc
            ),
            visits as (
                select queue_entry_id,
                       min(occurred_at) filter (where event_type = 'WAITING')          as joined_at,
                       min(occurred_at) filter (where event_type = 'CALLED')           as called_at,
                       min(occurred_at) filter (where event_type = 'IN_CONSULTATION')  as started_at,
                       min(occurred_at) filter (where event_type = 'COMPLETED')        as completed_at
                from ev
                where queue_entry_id is not null
                group by queue_entry_id
            ),
            recent as (
                select extract(epoch from completed_at - started_at) / 60 as minutes
                from visits
                where completed_at >= :dayStart and started_at is not null
                order by completed_at desc
                limit 3
            )
            select
                (select count(*) from entry_state where event_type in ('WAITING', 'REQUEUED'))
                    as queue_length,
                (select count(*) from entry_state
                  where event_type in ('WAITING', 'REQUEUED') and token_number < :token
                    and queue_entry_id <> :entry)
                    as patients_ahead,
                (select count(*) from today where event_type = 'COMPLETED')
                    as completed_today,
                (select extract(epoch from :asOf - max(t.occurred_at)) / 60
                   from entry_state s
                   join today t on t.queue_entry_id = s.queue_entry_id and t.event_type = 'CALLED'
                  where s.event_type in ('CALLED', 'IN_CONSULTATION'))
                    as active_patient_minutes,
                (select count(*) from today
                  where event_type = 'CALLED' and occurred_at > :asOf - interval '60 minutes')
                    as calls_last_hour,
                (select avg(minutes) from recent)
                    as recent_consultation_minutes,
                (select avg(extract(epoch from completed_at - started_at) / 60) from visits
                  where completed_at < :dayStart and started_at is not null)
                    as historical_consultation_minutes,
                (select count(*) from visits
                  where completed_at < :dayStart and started_at is not null)
                    as historical_consultation_count,
                (select avg(extract(epoch from called_at - joined_at) / 60) from visits
                  where joined_at < :dayStart and called_at is not null
                    and extract(hour from joined_at at time zone :zone) between :hour - 1 and :hour + 1)
                    as historical_wait_minutes
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WaitTimeFeatureBuilder(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param entryId  the waiting patient's queue entry
     * @param token    their token (patients ahead are those waiting with a lower one)
     * @param asOf     the moment of the prediction
     * @param upToSeq  last history event to consider; {@link Long#MAX_VALUE} for "everything so far"
     */
    public WaitTimeFeatures at(
            UUID clinicId, UUID doctorId, UUID entryId, int token, Instant asOf, long upToSeq, ZoneId zone
    ) {
        ZonedDateTime local = asOf.atZone(zone);
        LocalDate day = local.toLocalDate();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clinic", clinicId)
                .addValue("doctor", doctorId)
                .addValue("entry", entryId)
                .addValue("token", token)
                .addValue("asOf", OffsetDateTime.ofInstant(asOf, zone))
                .addValue("upToSeq", upToSeq)
                .addValue("dayStart", day.atStartOfDay(zone).toOffsetDateTime())
                .addValue("historyStart", day.minusDays(HISTORY_DAYS).atStartOfDay(zone).toOffsetDateTime())
                .addValue("zone", zone.getId())
                .addValue("hour", local.getHour());

        return jdbc.queryForObject(QUERY, params, (rs, i) -> {
            Double active = nullableDouble(rs.getObject("active_patient_minutes"));
            return new WaitTimeFeatures(
                    local.getDayOfWeek().getValue(),
                    local.getHour() * 60 + local.getMinute() + local.getSecond() / 60.0,
                    rs.getInt("patients_ahead"),
                    rs.getInt("queue_length"),
                    rs.getInt("completed_today"),
                    active == null ? 0 : 1,
                    active == null ? 0 : active,
                    rs.getInt("calls_last_hour"),
                    nullableDouble(rs.getObject("recent_consultation_minutes")),
                    nullableDouble(rs.getObject("historical_consultation_minutes")),
                    rs.getInt("historical_consultation_count"),
                    nullableDouble(rs.getObject("historical_wait_minutes")));
        });
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
