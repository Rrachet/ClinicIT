package com.clinicit.notification.application;

/**
 * A provider did not accept a message.
 *
 * @param code short, non-sensitive reason stored on the notification (never the phone number or body)
 * @param retryable false for permanent problems such as an invalid number
 */
public class NotificationDeliveryException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public NotificationDeliveryException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
