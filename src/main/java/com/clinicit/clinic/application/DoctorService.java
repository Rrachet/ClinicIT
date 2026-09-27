package com.clinicit.clinic.application;

import com.clinicit.clinic.api.CreateDoctorRequest;
import com.clinicit.clinic.api.DoctorResponse;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.identity.domain.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class DoctorService {

    private final DoctorProfileRepository doctors;

    public DoctorService(DoctorProfileRepository doctors) {
        this.doctors = doctors;
    }

    public DoctorResponse create(Actor admin, CreateDoctorRequest request) {
        DoctorProfile doctor = new DoctorProfile();
        doctor.setClinicId(admin.clinicId());
        doctor.setDisplayName(request.displayName().trim());
        doctor.setSpecialization(request.specialization());
        return DoctorResponse.from(doctors.save(doctor));
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> list(Actor actor) {
        return doctors.findByClinicIdOrderByDisplayNameAsc(actor.clinicId()).stream()
                .map(DoctorResponse::from)
                .toList();
    }
}
