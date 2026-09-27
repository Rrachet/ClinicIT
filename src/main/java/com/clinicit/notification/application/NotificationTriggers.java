package com.clinicit.notification.application;

import com.clinicit.appointment.domain.AppointmentConfirmed;
import com.clinicit.queue.application.QueueEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Connects clinic/queue domain events to notifications. Listeners run synchronously in
 * the publishing transaction so the message intent commits (or rolls back) with the change.
 *
 * <p>Notifications must never break a clinic operation: anything thrown while deciding
 * what to send is logged and swallowed here. (The planner uses only JDBC with conflict-free
 * inserts, so it does not leave the transaction in a failed state.)
 */
@Component
public class NotificationTriggers {

    private static final Logger log = LoggerFactory.getLogger(NotificationTriggers.class);

    private final NotificationService notifications;

    public NotificationTriggers(NotificationService notifications) {
        this.notifications = notifications;
    }

    @EventListener
    public void onQueueEvent(QueueEventRecorder.Recorded recorded) {
        try {
            notifications.queueEventOccurred(recorded.message());
        } catch (RuntimeException e) {
            log.error("Could not plan notifications for queue event {}; the queue change is unaffected",
                    recorded.message().eventId(), e);
        }
    }

    @EventListener
    public void onAppointmentConfirmed(AppointmentConfirmed confirmed) {
        try {
            notifications.appointmentConfirmed(confirmed.appointmentId());
        } catch (RuntimeException e) {
            log.error("Could not plan the confirmation notification for appointment {}; the confirmation is unaffected",
                    confirmed.appointmentId(), e);
        }
    }
}
