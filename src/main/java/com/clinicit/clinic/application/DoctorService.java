package com.clinicit.clinic.application;

import com.clinicit.clinic.api.CreateDoctorRequest;
import com.clinicit.clinic.api.DoctorResponse;
import com.clinicit.clinic.api.DutyRequest;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DoctorService {

    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;

    public DoctorService(DoctorProfileRepository doctors, ClinicTime clinicTime) {
        this.doctors = doctors;
        this.clinicTime = clinicTime;
    }

    public DoctorResponse create(Actor admin, CreateDoctorRequest request) {
        DoctorProfile doctor = new DoctorProfile();
        doctor.setClinicId(admin.clinicId());
        doctor.setDisplayName(request.displayName().trim());
        doctor.setSpecialization(request.specialization());
        doctor.setRoom(blankToNull(request.room()));
        return DoctorResponse.from(doctors.save(doctor), clinicTime.today(admin.clinicId()));
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> list(Actor actor) {
        var today = clinicTime.today(actor.clinicId());
        return doctors.findByClinicIdOrderByDisplayNameAsc(actor.clinicId()).stream()
                .map(doctor -> DoctorResponse.from(doctor, today))
                .toList();
    }

    /**
     * A doctor goes live (ready to see patients, so self check-in may send patients to them)
     * or offline. Doctors change only themselves; front-desk roles may change any doctor, for
     * example when a doctor forgot to go offline. Patients already queued stay queued.
     */
    public DoctorResponse setDuty(Actor actor, UUID doctorId, DutyRequest request) {
        actor.requireAccessToDoctor(doctorId);
        // The row lock orders this with self check-in, which reads who is live.
        DoctorProfile doctor = doctors.findByIdAndClinicIdForUpdate(doctorId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        var today = clinicTime.today(actor.clinicId());
        if (request.room() != null) {
            doctor.setRoom(blankToNull(request.room()));
        }
        if (request.live()) {
            doctor.goOnDuty(today, clinicTime.instant());
        } else {
            doctor.goOffDuty();
        }
        return DoctorResponse.from(doctor, today);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
