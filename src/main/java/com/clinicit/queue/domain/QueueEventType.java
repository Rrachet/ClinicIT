package com.clinicit.queue.domain;

/** What happened to a queue entry. Derived from the transition, never chosen by callers. */
public enum QueueEventType {
    PATIENT_JOINED_QUEUE,
    PATIENT_CALLED,
    PATIENT_STARTED_CONSULTATION,
    PATIENT_COMPLETED,
    PATIENT_SKIPPED,
    PATIENT_REQUEUED,
    PATIENT_NO_SHOW;

    /**
     * @param previous status before the change, or null when the entry was just created
     */
    public static QueueEventType of(QueueStatus previous, QueueStatus current) {
        return switch (current) {
            case WAITING -> previous == null ? PATIENT_JOINED_QUEUE : PATIENT_REQUEUED;
            case CALLED -> PATIENT_CALLED;
            case IN_CONSULTATION -> PATIENT_STARTED_CONSULTATION;
            case COMPLETED -> PATIENT_COMPLETED;
            case SKIPPED -> PATIENT_SKIPPED;
            case NO_SHOW -> PATIENT_NO_SHOW;
        };
    }
}
