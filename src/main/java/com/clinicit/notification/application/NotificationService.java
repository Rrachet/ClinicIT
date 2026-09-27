package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationType;
import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.domain.QueueEventType;
import com.clinicit.queue.domain.QueueStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which patient messages a clinic/queue change deserves and records them.
 *
 * <p>Runs inside the transaction of the change (it is called from event listeners), so a
 * message exists if and only if the change committed. Only plain JDBC is used, and inserts
 * are {@code ON CONFLICT DO NOTHING}: a duplicate event cannot raise an error that would
 * abort the caller's transaction. Delivery happens later, after commit
 * ({@link NotificationDispatcher}).
 *
 * <p>What is worth a message:
 * <ul>
 *   <li>APPOINTMENT_CONFIRMED: once per appointment, and only if it is still useful
 *       (more than {@link #CONFIRMATION_LEAD} before the appointment; not for walk-ins)</li>
 *   <li>PATIENT_JOINED_QUEUE: once per queue entry, with the status link</li>
 *   <li>PATIENT_CALLED: each time the patient is called</li>
 *   <li>PATIENT_NEAR_TURN: once per queue entry, when the line moves and at most
 *       {@code nearTurnThreshold} patients are ahead</li>
 * </ul>
 * Starting, completing, skipping, requeueing and no-shows do not message the patient.
 */
@Service
public class NotificationService {

    static final Duration CONFIRMATION_LEAD = Duration.ofMinutes(30);
    static final Duration CALLED_VALID_FOR = Duration.ofMinutes(10);
    static final Duration NEAR_TURN_VALID_FOR = Duration.ofMinutes(30);

    /** Published after rows are inserted; the dispatcher sends them once the transaction commits. */
    public record Queued(List<UUID> notificationIds) {}

    private static final String INSERT = """
            insert into notifications (id, clinic_id, appointment_id, queue_entry_id, type, channel, recipient, body,
                                       status, dedupe_key, attempts, max_attempts, next_attempt_at, expires_at,
                                       created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, ?, ?, ?, ?, ?)
            on conflict (dedupe_key) do nothing
            """;

    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationService(
            JdbcTemplate jdbc, NotificationProperties properties, ApplicationEventPublisher events, Clock clock
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appointmentConfirmed(UUID appointmentId) {
        if (!properties.enabled()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.clinic_id, a.scheduled_at, p.phone, c.name as clinic_name, c.timezone
                from appointments a
                join patients p on p.id = a.patient_id
                join clinics c on c.id = a.clinic_id
                where a.id = ?
                """, appointmentId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.getFirst();

        LocalDateTime scheduledAt = ((Timestamp) row.get("scheduled_at")).toLocalDateTime();
        Instant appointmentInstant = scheduledAt.atZone(ZoneId.of((String) row.get("timezone"))).toInstant();
        Instant now = clock.instant();
        if (!now.isBefore(appointmentInstant.minus(CONFIRMATION_LEAD))) {
            return; // walk-in or imminent: the patient is (nearly) here already
        }

        List<UUID> created = new ArrayList<>();
        insert(created, (UUID) row.get("clinic_id"), appointmentId, null, NotificationType.APPOINTMENT_CONFIRMED,
                (String) row.get("phone"),
                NotificationTemplates.appointmentConfirmed((String) row.get("clinic_name"), scheduledAt),
                "APPOINTMENT_CONFIRMED:" + appointmentId, appointmentInstant, now);
        announce(created);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void queueEventOccurred(QueueEventMessage event) {
        if (!properties.enabled()) return;
        List<UUID> created = new ArrayList<>();
        Instant now = clock.instant();

        if (event.type() == QueueEventType.PATIENT_JOINED_QUEUE) {
            EntryContext entry = entry(event.queueEntryId());
            if (entry != null) {
                insert(created, entry, NotificationType.PATIENT_JOINED_QUEUE,
                        NotificationTemplates.joinedQueue(entry.clinicName, entry.token, properties.statusLink(entry.statusCode)),
                        "PATIENT_JOINED_QUEUE:" + entry.id, entry.endOfQueueDay(), now);
            }
        }
        if (event.type() == QueueEventType.PATIENT_CALLED) {
            EntryContext entry = entry(event.queueEntryId());
            if (entry != null) {
                insert(created, entry, NotificationType.PATIENT_CALLED,
                        NotificationTemplates.called(entry.clinicName, entry.token),
                        "PATIENT_CALLED:" + entry.id + ":" + event.entryVersion(), now.plus(CALLED_VALID_FOR), now);
            }
        }
        if (lineMoved(event)) {
            for (UUID waiting : waitingWithinThreshold(event.doctorId(), event.queueDate())) {
                EntryContext entry = entry(waiting);
                if (entry != null) {
                    insert(created, entry, NotificationType.PATIENT_NEAR_TURN,
                            NotificationTemplates.nearTurn(entry.clinicName, entry.token, properties.statusLink(entry.statusCode)),
                            "PATIENT_NEAR_TURN:" + entry.id, now.plus(NEAR_TURN_VALID_FOR), now);
                }
            }
        }
        announce(created);
    }

    /** The line only moves forward when a waiting patient leaves it. */
    private static boolean lineMoved(QueueEventMessage event) {
        return event.previousStatus() == QueueStatus.WAITING
                && (event.type() == QueueEventType.PATIENT_CALLED || event.type() == QueueEventType.PATIENT_SKIPPED);
    }

    private List<UUID> waitingWithinThreshold(UUID doctorId, LocalDate queueDate) {
        return jdbc.queryForList("""
                select id from (
                    select id, row_number() over (order by token_number) - 1 as ahead
                    from queue_entries
                    where doctor_id = ? and queue_date = ? and status = 'WAITING'
                ) line
                where ahead <= ?
                """, UUID.class, doctorId, queueDate, properties.nearTurnThreshold());
    }

    private record EntryContext(UUID id, UUID clinicId, UUID appointmentId, int token, String statusCode,
                                String phone, String clinicName, LocalDate queueDate, ZoneId zone) {
        Instant endOfQueueDay() {
            return queueDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
    }

    private EntryContext entry(UUID queueEntryId) {
        List<EntryContext> found = jdbc.query("""
                select q.id, q.clinic_id, q.appointment_id, q.token_number, q.status_code, q.queue_date,
                       p.phone, c.name as clinic_name, c.timezone
                from queue_entries q
                join appointments a on a.id = q.appointment_id
                join patients p on p.id = a.patient_id
                join clinics c on c.id = q.clinic_id
                where q.id = ?
                """, (rs, i) -> new EntryContext(
                rs.getObject("id", UUID.class), rs.getObject("clinic_id", UUID.class),
                rs.getObject("appointment_id", UUID.class), rs.getInt("token_number"), rs.getString("status_code"),
                rs.getString("phone"), rs.getString("clinic_name"), rs.getObject("queue_date", LocalDate.class),
                ZoneId.of(rs.getString("timezone"))), queueEntryId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private void insert(List<UUID> created, EntryContext entry, NotificationType type, String body,
                        String dedupeKey, Instant expiresAt, Instant now) {
        insert(created, entry.clinicId, entry.appointmentId, entry.id, type, entry.phone, body, dedupeKey, expiresAt, now);
    }

    private void insert(List<UUID> created, UUID clinicId, UUID appointmentId, UUID queueEntryId, NotificationType type,
                        String recipient, String body, String dedupeKey, Instant expiresAt, Instant now) {
        if (recipient == null || recipient.isBlank() || !expiresAt.isAfter(now)) {
            return;
        }
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        int inserted = jdbc.update(INSERT, id, clinicId, appointmentId, queueEntryId, type.name(),
                properties.channel().name(), recipient.trim(), body, dedupeKey, properties.maxAttempts(),
                at, Timestamp.from(expiresAt), at, at);
        if (inserted == 1) {
            created.add(id);
        }
    }

    private void announce(List<UUID> created) {
        if (!created.isEmpty()) {
            events.publishEvent(new Queued(List.copyOf(created)));
        }
    }
}
