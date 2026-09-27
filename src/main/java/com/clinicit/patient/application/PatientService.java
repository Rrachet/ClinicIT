package com.clinicit.patient.application;

import com.clinicit.patient.api.PatientRequest;
import com.clinicit.patient.api.PatientResponse;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PatientService {

    private final PatientRepository repository;

    public PatientService(PatientRepository repository) {
        this.repository = repository;
    }

    public PatientResponse create(PatientRequest request) {
        Patient patient = new Patient();
        patient.setClinicId(request.clinicId());
        patient.setFullName(request.fullName().trim());
        patient.setPhone(request.phone().trim());
        patient.setDateOfBirth(request.dateOfBirth());

        return PatientResponse.from(repository.save(patient));
    }

    @Transactional(readOnly = true)
    public PatientResponse get(UUID id) {
        return repository.findById(id)
                .map(PatientResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found"));
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> search(UUID clinicId, String name) {
        return repository
                .findTop20ByClinicIdAndFullNameContainingIgnoreCaseOrderByFullNameAsc(clinicId, name)
                .stream()
                .map(PatientResponse::from)
                .toList();
    }
}
