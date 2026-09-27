package com.clinicit.queue.api;

import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One doctor's queue for one day, as shown on the receptionist and doctor screens.
 *
 * @param currentToken token of the patient currently CALLED or IN_CONSULTATION, or null
 * @param waitingCount patients in WAITING
 */
public record QueueBoardResponse(
        UUID doctorId,
        LocalDate queueDate,
        Integer currentToken,
        int waitingCount,
        List<Entry> entries
) {

    /**
     * @param patientsAhead for WAITING entries, how many WAITING patients hold a lower
     *                      token; null for any other status
     * @param version       the entry's version; compare with entryVersion of real-time events
     *                      so a board reload never overwrites a newer event (or vice versa)
     */
    public record Entry(
            UUID id,
            UUID appointmentId,
            int tokenNumber,
            QueueStatus status,
            String patientName,
            Integer patientsAhead,
            Instant checkedInAt,
            Instant calledAt,
            long version,
            String statusCode
    ) {}

    public static QueueBoardResponse from(
            UUID doctorId,
            LocalDate queueDate,
            List<QueueEntry> queueInTokenOrder,
            Map<UUID, String> patientNameByAppointment
    ) {
        Integer currentToken = null;
        int waitingSoFar = 0;
        List<Entry> entries = new ArrayList<>(queueInTokenOrder.size());

        for (QueueEntry q : queueInTokenOrder) {
            Integer ahead = null;
            if (q.getStatus() == QueueStatus.WAITING) {
                ahead = waitingSoFar++;
            } else if (QueueStatus.ACTIVE.contains(q.getStatus())) {
                currentToken = q.getTokenNumber();
            }
            entries.add(new Entry(
                    q.getId(),
                    q.getAppointmentId(),
                    q.getTokenNumber(),
                    q.getStatus(),
                    patientNameByAppointment.get(q.getAppointmentId()),
                    ahead,
                    q.getCheckedInAt(),
                    q.getCalledAt(),
                    q.getVersion(),
                    q.getStatusCode()
            ));
        }

        return new QueueBoardResponse(doctorId, queueDate, currentToken, waitingSoFar, entries);
    }
}
