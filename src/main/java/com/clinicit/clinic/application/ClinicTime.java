package com.clinicit.clinic.application;

import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.common.domain.NotFoundException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Clinic-local time. Appointments are scheduled in the clinic's wall-clock time and
 * "today's queue" is the clinic's calendar day, never the server's.
 */
@Component
public class ClinicTime {

    private final ClinicRepository clinics;
    private final Clock clock;

    public ClinicTime(ClinicRepository clinics, Clock clock) {
        this.clinics = clinics;
        this.clock = clock;
    }

    public Instant instant() {
        return clock.instant();
    }

    public ZoneId zone(UUID clinicId) {
        return clinics.findById(clinicId)
                .map(clinic -> ZoneId.of(clinic.getTimezone()))
                .orElseThrow(() -> new NotFoundException("Clinic not found"));
    }

    public LocalDate today(UUID clinicId) {
        return LocalDate.now(clock.withZone(zone(clinicId)));
    }

    public LocalDateTime now(UUID clinicId) {
        return LocalDateTime.now(clock.withZone(zone(clinicId)));
    }
}
