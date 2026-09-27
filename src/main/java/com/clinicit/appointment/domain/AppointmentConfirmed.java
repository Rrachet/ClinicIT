package com.clinicit.appointment.domain;

import java.util.UUID;

/**
 * Raised inside the transaction that confirms an appointment. Other modules (e.g.
 * notifications) react to it; the appointment module does not know who listens.
 */
public record AppointmentConfirmed(UUID appointmentId, UUID clinicId) {}
