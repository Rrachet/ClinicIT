package com.clinicit.appointment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Appointment lifecycle.
 *
 * <pre>
 * BOOKED → CONFIRMED → ARRIVED → WAITING → CALLED → IN_CONSULTATION → COMPLETED
 *
 * BOOKED, CONFIRMED → CANCELLED
 * CONFIRMED         → NO_SHOW   (never arrived; recorded for no-show history/prediction)
 * ARRIVED           → NO_SHOW   (arrived but left before joining the queue)
 * WAITING, CALLED   → SKIPPED   (stepped away / did not answer the call)
 * SKIPPED           → WAITING   (came back, keeps the original token)
 * SKIPPED           → NO_SHOW   (never came back)
 * </pre>
 *
 * WAITING onwards are driven by the queue engine; the appointment mirrors its queue entry
 * and must not be changed directly (see {@link #isQueueManaged()}).
 */
public enum AppointmentStatus {
    BOOKED,
    CONFIRMED,
    ARRIVED,
    WAITING,
    CALLED,
    IN_CONSULTATION,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    SKIPPED;

    public Set<AppointmentStatus> allowedTargets() {
        return switch (this) {
            case BOOKED -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(ARRIVED, CANCELLED, NO_SHOW);
            case ARRIVED -> EnumSet.of(WAITING, NO_SHOW);
            case WAITING -> EnumSet.of(CALLED, SKIPPED);
            case CALLED -> EnumSet.of(IN_CONSULTATION, SKIPPED);
            case IN_CONSULTATION -> EnumSet.of(COMPLETED);
            case SKIPPED -> EnumSet.of(WAITING, NO_SHOW);
            case COMPLETED, CANCELLED, NO_SHOW -> EnumSet.noneOf(AppointmentStatus.class);
        };
    }

    public boolean canTransitionTo(AppointmentStatus target) {
        return allowedTargets().contains(target);
    }

    /** Statuses owned by the patient's queue entry; only the queue engine may move them. */
    public boolean isQueueManaged() {
        return this == WAITING || this == CALLED || this == IN_CONSULTATION || this == SKIPPED;
    }
}
