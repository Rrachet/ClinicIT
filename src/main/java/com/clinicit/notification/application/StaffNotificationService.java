package com.clinicit.notification.application;

import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.notification.api.NotificationResponse;
import com.clinicit.notification.domain.Notification;
import com.clinicit.notification.domain.NotificationRepository;
import com.clinicit.notification.domain.NotificationStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Front-desk view of patient messages, always within the caller's clinic. */
@Service
@Transactional
public class StaffNotificationService {

    private final NotificationRepository notifications;
    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StaffNotificationService(
            NotificationRepository notifications,
            JdbcTemplate jdbc,
            NotificationProperties properties,
            ApplicationEventPublisher events,
            Clock clock
    ) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> forAppointment(Actor actor, UUID appointmentId) {
        return notifications.findByClinicIdAndAppointmentIdOrderByCreatedAtAsc(actor.clinicId(), appointmentId)
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    /**
     * Gives a FAILED message a fresh set of attempts and sends it after commit. Refused
     * once the message is no longer true (e.g. "please go in now" after its window).
     */
    public NotificationResponse retry(Actor actor, UUID notificationId) {
        Notification notification = notifications.findByIdAndClinicId(notificationId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        Instant now = clock.instant();
        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new BusinessRuleException("NOTIFICATION_NOT_FAILED", "Only failed messages can be retried");
        }
        if (!notification.getExpiresAt().isAfter(now)) {
            throw new BusinessRuleException("NOTIFICATION_EXPIRED", "This message is out of date and can no longer be sent");
        }
        jdbc.update("""
                update notifications set status = 'PENDING', max_attempts = attempts + ?, next_attempt_at = ?, updated_at = ?
                where id = ? and status = 'FAILED'
                """, properties.maxAttempts(), Timestamp.from(now), Timestamp.from(now), notificationId);
        events.publishEvent(new NotificationService.Queued(List.of(notificationId)));
        return notifications.findById(notificationId).map(NotificationResponse::from).orElseThrow();
    }
}
