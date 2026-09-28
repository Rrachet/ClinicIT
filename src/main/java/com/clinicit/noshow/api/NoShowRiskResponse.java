package com.clinicit.noshow.api;

import com.clinicit.noshow.domain.NoShowRisk;

import java.util.UUID;

/**
 * An advisory flag for the front desk. {@code reason} says in words what it is based on, so
 * nobody has to trust a number: e.g. "Missed 3 of their last 5 appointments here".
 */
public record NoShowRiskResponse(UUID appointmentId, NoShowRisk.Level level, int priorAppointments, int priorMissed,
                                 String reason) {

    public static NoShowRiskResponse of(UUID appointmentId, NoShowRisk risk) {
        String reason = switch (risk.level()) {
            case UNKNOWN -> "Not enough earlier appointments to judge";
            case TYPICAL, ELEVATED -> "Missed %d of their last %d appointments here"
                    .formatted(risk.priorMissed(), risk.priorAppointments());
        };
        return new NoShowRiskResponse(appointmentId, risk.level(), risk.priorAppointments(), risk.priorMissed(), reason);
    }
}
