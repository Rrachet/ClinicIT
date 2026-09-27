package com.clinicit.queue.api;

import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QueueEntryResponse(
        UUID id,
        UUID appointmentId,
        UUID clinicId,
        UUID doctorId,
        LocalDate queueDate,
        int tokenNumber,
        QueueStatus status,
        Instant checkedInAt,
        Instant calledAt,
        Instant consultationStartedAt,
        Instant completedAt,
        Instant skippedAt
) {
    public static QueueEntryResponse from(QueueEntry entry) {
        return new QueueEntryResponse(
                entry.getId(),
                entry.getAppointmentId(),
                entry.getClinicId(),
                entry.getDoctorId(),
                entry.getQueueDate(),
                entry.getTokenNumber(),
                entry.getStatus(),
                entry.getCheckedInAt(),
                entry.getCalledAt(),
                entry.getConsultationStartedAt(),
                entry.getCompletedAt(),
                entry.getSkippedAt()
        );
    }
}
