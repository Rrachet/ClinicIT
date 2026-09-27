package com.clinicit.notification.domain;

/** PENDING (waiting or retrying), SENT, or FAILED (gave up, expired, or not deliverable). */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
