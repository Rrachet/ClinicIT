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
 * ARRIVED           → NO_SHOW   (arrived but left before joining the queue)
 * WAITING, CALLED   → SKIPPED   (stepped away / did not answer the call)
 * SKIPPED           → WAITING   (came back, keeps the original token)
 * SKIPPED           → NO_SHOW   (never came back)
 * </pre>
 *
 * WAITING onwards are driven by the queue engine; the appointment mirrors its queue entry.
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
            case CONFIRMED -> EnumSet.of(ARRIVED, CANCELLED);
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

    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
