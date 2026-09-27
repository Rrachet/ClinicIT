package com.clinicit.queue.api;

import com.clinicit.queue.domain.QueueStatus;

import java.time.LocalDate;

/**
 * What an anonymous holder of a status link may see: their own token and position.
 * No patient name, phone, appointment details or internal ids.
 *
 * @param currentToken  token currently called or in consultation with this doctor, or null
 * @param patientsAhead waiting patients with a lower token; 0 unless this entry is WAITING
 * @param estimatedWait approximate wait before being called, while WAITING; otherwise null
 */
public record PublicQueueStatusResponse(
        String clinicName,
        String doctorName,
        LocalDate queueDate,
        int tokenNumber,
        QueueStatus status,
        Integer currentToken,
        long patientsAhead,
        EstimatedWait estimatedWait
) {
    /** A range, never a promise. Operational only: says nothing about the patient's care. */
    public record EstimatedWait(int estimatedWaitMinutes, int lowerBoundMinutes, int upperBoundMinutes) {}
}
