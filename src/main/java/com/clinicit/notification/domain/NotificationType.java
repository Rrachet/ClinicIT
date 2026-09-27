package com.clinicit.notification.domain;

/** The only moments a patient is messaged. Minor queue changes never notify. */
public enum NotificationType {
    APPOINTMENT_CONFIRMED,
    PATIENT_JOINED_QUEUE,
    PATIENT_NEAR_TURN,
    PATIENT_CALLED
}
