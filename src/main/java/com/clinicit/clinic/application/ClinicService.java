package com.clinicit.clinic.application;

import com.clinicit.clinic.api.ClinicResponse;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ClinicService {

    private final ClinicRepository clinics;
    private final ClinicTime clinicTime;

    public ClinicService(ClinicRepository clinics, ClinicTime clinicTime) {
        this.clinics = clinics;
        this.clinicTime = clinicTime;
    }

    /**
     * Renames the clinic (shown on every screen and on patients' status pages). The timezone
     * is deliberately not editable here: it decides which day every queue belongs to, so
     * changing it during operation would move today's queue to another date.
     */
    @Transactional
    public ClinicResponse rename(Actor admin, String name) {
        Clinic clinic = clinics.findById(admin.clinicId()).orElseThrow(() -> new NotFoundException("Clinic not found"));
        clinic.setName(name.strip());
        return current(admin);
    }

    public ClinicResponse current(Actor actor) {
        return clinics.findById(actor.clinicId())
                .map(clinic -> new ClinicResponse(
                        clinic.getId(), clinic.getName(), clinic.getTimezone(), clinicTime.today(clinic.getId())))
                .orElseThrow(() -> new NotFoundException("Clinic not found"));
    }
}
