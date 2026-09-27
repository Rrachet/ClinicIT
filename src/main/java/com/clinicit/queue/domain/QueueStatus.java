package com.clinicit.queue.domain;

import com.clinicit.appointment.domain.AppointmentStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Queue entry lifecycle. An entry is created in WAITING when an ARRIVED
 * appointment joins the queue.
 *
 * <pre>
 * WAITING → CALLED → IN_CONSULTATION → COMPLETED
 *
 * WAITING, CALLED → SKIPPED
 * SKIPPED         → WAITING   (requeue with the original token)
 * SKIPPED         → NO_SHOW
 * </pre>
 *
 * Each queue status has the same-named appointment status, which the queue
 * engine keeps in sync inside the same transaction.
 */
public enum QueueStatus {
    WAITING,
    CALLED,
    IN_CONSULTATION,
    COMPLETED,
    SKIPPED,
    NO_SHOW;

    /** Statuses that occupy the doctor. At most one per doctor per day (enforced by a DB index). */
    public static final Set<QueueStatus> ACTIVE = EnumSet.of(CALLED, IN_CONSULTATION);

    public Set<QueueStatus> allowedTargets() {
        return switch (this) {
            case WAITING -> EnumSet.of(CALLED, SKIPPED);
            case CALLED -> EnumSet.of(IN_CONSULTATION, SKIPPED);
            case IN_CONSULTATION -> EnumSet.of(COMPLETED);
            case SKIPPED -> EnumSet.of(WAITING, NO_SHOW);
            case COMPLETED, NO_SHOW -> EnumSet.noneOf(QueueStatus.class);
        };
    }

    public boolean canTransitionTo(QueueStatus target) {
        return allowedTargets().contains(target);
    }

    public AppointmentStatus toAppointmentStatus() {
        return AppointmentStatus.valueOf(name());
    }
}
