package com.clinicit.queue.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.queue.api.PublicQueueStatusResponse;
import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueEntryRepository;
import com.clinicit.queue.domain.QueueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous, read-only view of one queue entry, looked up by its unguessable status code.
 * Unknown codes and codes from a past queue day both answer "not found", so the endpoint
 * cannot be used to probe which codes existed.
 */
@Service
@Transactional(readOnly = true)
public class PublicQueueStatusService {

    private final QueueEntryRepository entries;
    private final ClinicRepository clinics;
    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;

    public PublicQueueStatusService(
            QueueEntryRepository entries,
            ClinicRepository clinics,
            DoctorProfileRepository doctors,
            ClinicTime clinicTime
    ) {
        this.entries = entries;
        this.clinics = clinics;
        this.doctors = doctors;
        this.clinicTime = clinicTime;
    }

    public PublicQueueStatusResponse status(String code) {
        QueueEntry entry = entries.findByStatusCode(code)
                .filter(found -> !found.getQueueDate().isBefore(clinicTime.today(found.getClinicId())))
                .orElseThrow(() -> new NotFoundException("Queue status not found"));

        Integer currentToken = entries.findFirstByDoctorIdAndQueueDateAndStatusIn(
                        entry.getDoctorId(), entry.getQueueDate(), QueueStatus.ACTIVE)
                .map(QueueEntry::getTokenNumber)
                .orElse(null);
        long ahead = entry.getStatus() == QueueStatus.WAITING
                ? entries.countByDoctorIdAndQueueDateAndStatusAndTokenNumberLessThan(
                        entry.getDoctorId(), entry.getQueueDate(), QueueStatus.WAITING, entry.getTokenNumber())
                : 0;

        return new PublicQueueStatusResponse(
                clinics.findById(entry.getClinicId()).map(Clinic::getName).orElse(null),
                doctors.findById(entry.getDoctorId()).map(DoctorProfile::getDisplayName).orElse(null),
                entry.getQueueDate(),
                entry.getTokenNumber(),
                entry.getStatus(),
                currentToken,
                ahead
        );
    }
}
