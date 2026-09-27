package com.clinicit.queue.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.prediction.application.WaitTimePredictionService;
import com.clinicit.queue.api.PublicQueueStatusResponse;
import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueEntryRepository;
import com.clinicit.queue.domain.QueueStatus;
import org.springframework.stereotype.Service;

/**
 * Anonymous, read-only view of one queue entry, looked up by its unguessable status code.
 * Unknown codes and codes from a past queue day both answer "not found", so the endpoint
 * cannot be used to probe which codes existed.
 */
@Service
// Deliberately not @Transactional: the estimate may wait on the ML service, and no database
// connection should be held meanwhile. Each repository read is its own read-only transaction.
public class PublicQueueStatusService {

    private final QueueEntryRepository entries;
    private final ClinicRepository clinics;
    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;
    private final WaitTimePredictionService predictions;

    public PublicQueueStatusService(
            QueueEntryRepository entries,
            ClinicRepository clinics,
            DoctorProfileRepository doctors,
            ClinicTime clinicTime,
            WaitTimePredictionService predictions
    ) {
        this.entries = entries;
        this.clinics = clinics;
        this.doctors = doctors;
        this.clinicTime = clinicTime;
        this.predictions = predictions;
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
                doctors.findByIdAndClinicId(entry.getDoctorId(), entry.getClinicId())
                        .map(DoctorProfile::getDisplayName).orElse(null),
                entry.getQueueDate(),
                entry.getTokenNumber(),
                entry.getStatus(),
                currentToken,
                ahead,
                predictions.forEntry(entry)
                        .map(e -> new PublicQueueStatusResponse.EstimatedWait(
                                e.estimatedWaitMinutes(), e.lowerBoundMinutes(), e.upperBoundMinutes()))
                        .orElse(null)
        );
    }
}
