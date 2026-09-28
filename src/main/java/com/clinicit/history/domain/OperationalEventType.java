package com.clinicit.history.domain;

import com.clinicit.appointment.domain.AppointmentStatus;

/**
 * What happened to an appointment. Mostly the status it moved to, except that a skipped
 * patient returning to the queue is {@link #REQUEUED}, not a second {@link #WAITING}, so
 * {@code WAITING} always means "joined the queue" (exactly once per queue entry).
 */
public enum OperationalEventType {
    BOOKED,
    CONFIRMED,
    ARRIVED,
    WAITING,
    CALLED,
    IN_CONSULTATION,
    COMPLETED,
    CANCELLED,
    SKIPPED,
    REQUEUED,
    NO_SHOW,
    /** A booked or confirmed appointment moved to another time (the status does not change). */
    RESCHEDULED;

    public static OperationalEventType ofTransition(AppointmentStatus from, AppointmentStatus to) {
        if (from == AppointmentStatus.SKIPPED && to == AppointmentStatus.WAITING) {
            return REQUEUED;
        }
        if (to == AppointmentStatus.BOOKED) {
            throw new IllegalArgumentException("BOOKED is recorded on creation, not as a transition");
        }
        return valueOf(to.name());
    }
}
