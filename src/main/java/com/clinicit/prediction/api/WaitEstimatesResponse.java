package com.clinicit.prediction.api;

import com.clinicit.prediction.domain.WaitTimeEstimate;
import com.clinicit.queue.domain.QueueEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Estimated waits for everyone currently waiting for one doctor, in token order. */
public record WaitEstimatesResponse(UUID doctorId, LocalDate queueDate, List<Entry> entries) {

    /**
     * @param source       MODEL or BASELINE
     * @param modelVersion   the model or rule that produced the estimate
     * @param fallbackReason why the baseline was used (null when the model answered)
     */
    public record Entry(
            UUID queueEntryId,
            int tokenNumber,
            int estimatedWaitMinutes,
            int lowerBoundMinutes,
            int upperBoundMinutes,
            WaitTimeEstimate.Source source,
            String modelVersion,
            String fallbackReason
    ) {
        public static Entry from(QueueEntry entry, WaitTimeEstimate estimate) {
            return new Entry(entry.getId(), entry.getTokenNumber(), estimate.estimatedWaitMinutes(),
                    estimate.lowerBoundMinutes(), estimate.upperBoundMinutes(), estimate.source(),
                    estimate.modelVersion(), estimate.reason());
        }
    }
}
