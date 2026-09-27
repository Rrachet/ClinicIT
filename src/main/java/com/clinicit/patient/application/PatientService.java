package com.clinicit.patient.application;

import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.patient.api.PatientRequest;
import com.clinicit.patient.api.PatientResponse;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Patients always belong to, and are only visible within, the caller's clinic. */
@Service
@Transactional
public class PatientService {

    private final PatientRepository repository;

    public PatientService(PatientRepository repository) {
        this.repository = repository;
    }

    public PatientResponse create(Actor actor, PatientRequest request) {
        Patient patient = new Patient();
        patient.setClinicId(actor.clinicId());
        patient.setFullName(request.fullName().trim());
        patient.setPhone(request.phone().trim());
        patient.setDateOfBirth(request.dateOfBirth());

        return PatientResponse.from(repository.save(patient));
    }

    @Transactional(readOnly = true)
    public PatientResponse get(Actor actor, UUID id) {
        return repository.findByIdAndClinicId(id, actor.clinicId())
                .map(PatientResponse::from)
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> search(Actor actor, String name) {
        return repository
                .findTop20ByClinicIdAndFullNameContainingIgnoreCaseOrderByFullNameAsc(actor.clinicId(), name)
                .stream()
                .map(PatientResponse::from)
                .toList();
    }
}
