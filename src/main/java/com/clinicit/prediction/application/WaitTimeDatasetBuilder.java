package com.clinicit.prediction.application;

import com.clinicit.prediction.domain.WaitTimeFeatures;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the wait-time training dataset from the operational history: one example per
 * historical moment at which ClinicIT could have shown a waiting patient an estimate.
 *
 * <p><b>Moments ("snapshots").</b> The moment the patient joined the queue, and every later
 * moment, before they were first called, at which the number of patients ahead of them
 * changed (someone ahead was called or skipped, or came back after a skip). These are the
 * moments the product refreshes the estimate.
 *
 * <p><b>Target.</b> {@code actual_wait_minutes}: from the moment to the patient's first call.
 * For the join snapshot this is the full wait. Patients who were skipped or left before
 * being called have no observed wait and are left out (see docs/AI.md, limitations).
 *
 * <p><b>Features</b> come from {@link WaitTimeFeatureBuilder} "as of" the moment, pinned to
 * the event that created it, so no example can see anything that happened after it. The
 * target is the only value taken from the future, and it is never a feature.
 *
 * <p>The output has no identifiers (clinic, doctor, patient or entry ids): the model does
 * not use them, and a dataset without them is safer to move around. Rows are sorted by time
 * so the same history always gives the same file.
 */
@Component
public class WaitTimeDatasetBuilder {

    public static final List<String> COLUMNS = List.of(
            "local_date", "prediction_time", "snapshot",
            "day_of_week", "minute_of_day", "patients_ahead", "queue_length", "completed_today",
            "doctor_busy", "active_patient_minutes", "calls_last_hour",
            "recent_consultation_minutes", "historical_consultation_minutes", "historical_consultation_count",
            "historical_wait_minutes",
            "actual_wait_minutes");

    /**
     * Each patient's first waiting period (join → first event that ended it), kept only when
     * it ended with a call, plus every moment in between that changed who was ahead.
     */
    private static final String SNAPSHOTS = """
            with joins as (
                select e.clinic_id, e.doctor_id, e.queue_entry_id, e.token_number, e.seq, e.occurred_at
                from operational_events e
                where e.event_type = 'WAITING'
            ),
            periods as (
                select distinct on (j.queue_entry_id)
                       j.*, x.seq as end_seq, x.event_type as end_type, x.occurred_at as end_at
                from joins j
                join operational_events x
                  on x.queue_entry_id = j.queue_entry_id and x.seq > j.seq and x.previous_status = 'WAITING'
                order by j.queue_entry_id, x.seq
            ),
            called as (
                select * from periods where end_type = 'CALLED'
            ),
            moments as (
                select c.clinic_id, c.doctor_id, c.queue_entry_id, c.token_number, c.end_at,
                       c.seq as snapshot_seq, c.occurred_at as snapshot_at, 'JOIN' as snapshot
                from called c
                union all
                select c.clinic_id, c.doctor_id, c.queue_entry_id, c.token_number, c.end_at,
                       s.seq, s.occurred_at, 'QUEUE_MOVED'
                from called c
                join operational_events s
                  on s.clinic_id = c.clinic_id and s.doctor_id = c.doctor_id
                 and s.seq > c.seq and s.seq < c.end_seq
                 and s.queue_entry_id <> c.queue_entry_id and s.token_number < c.token_number
                 and (s.previous_status = 'WAITING' or s.event_type = 'REQUEUED')
            )
            select m.*, k.timezone
            from moments m
            join clinics k on k.id = m.clinic_id
            where (cast(? as uuid) is null or m.clinic_id = cast(? as uuid))
            order by m.snapshot_at, m.snapshot_seq, m.token_number
            """;

    public record Example(LocalDate localDate, Instant predictionTime, String snapshot,
                          WaitTimeFeatures features, double actualWaitMinutes) {}

    private final JdbcTemplate jdbc;
    private final WaitTimeFeatureBuilder features;

    public WaitTimeDatasetBuilder(JdbcTemplate jdbc, WaitTimeFeatureBuilder features) {
        this.jdbc = jdbc;
        this.features = features;
    }

    /** All clinics when {@code clinicId} is null. */
    public List<Example> build(UUID clinicId) {
        record Moment(UUID clinicId, UUID doctorId, UUID entryId, int token, Instant endAt,
                      long seq, Instant at, String snapshot, ZoneId zone) {}

        List<Moment> moments = jdbc.query(SNAPSHOTS, (rs, i) -> new Moment(
                rs.getObject("clinic_id", UUID.class),
                rs.getObject("doctor_id", UUID.class),
                rs.getObject("queue_entry_id", UUID.class),
                rs.getInt("token_number"),
                rs.getTimestamp("end_at").toInstant(),
                rs.getLong("snapshot_seq"),
                rs.getTimestamp("snapshot_at").toInstant(),
                rs.getString("snapshot"),
                ZoneId.of(rs.getString("timezone"))), clinicId, clinicId);

        List<Example> examples = new ArrayList<>(moments.size());
        for (Moment m : moments) {
            WaitTimeFeatures f = features.at(m.clinicId(), m.doctorId(), m.entryId(), m.token(), m.at(), m.seq(), m.zone());
            double target = (m.endAt().toEpochMilli() - m.at().toEpochMilli()) / 60_000.0;
            examples.add(new Example(m.at().atZone(m.zone()).toLocalDate(), m.at(), m.snapshot(), f, target));
        }
        return examples;
    }

    public void writeCsv(List<Example> examples, Writer out) throws IOException {
        out.write(String.join(",", COLUMNS));
        out.write('\n');
        for (Example e : examples) {
            WaitTimeFeatures f = e.features();
            List<String> cells = List.of(
                    e.localDate().toString(),
                    e.predictionTime().toString(),
                    e.snapshot(),
                    Integer.toString(f.dayOfWeek()),
                    number(f.minuteOfDay()),
                    Integer.toString(f.patientsAhead()),
                    Integer.toString(f.queueLength()),
                    Integer.toString(f.completedToday()),
                    Integer.toString(f.doctorBusy()),
                    number(f.activePatientMinutes()),
                    Integer.toString(f.callsLastHour()),
                    number(f.recentConsultationMinutes()),
                    number(f.historicalConsultationMinutes()),
                    Integer.toString(f.historicalConsultationCount()),
                    number(f.historicalWaitMinutes()),
                    number(e.actualWaitMinutes()));
            out.write(String.join(",", cells));
            out.write('\n');
        }
    }

    private static String number(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.3f", value);
    }
}
