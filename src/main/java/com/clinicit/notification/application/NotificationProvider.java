package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationChannel;

/**
 * The port every delivery vendor implements (SMS gateway, WhatsApp Business API, SMTP...).
 * Nothing in the clinic or queue modules depends on an implementation; see
 * docs/NOTIFICATIONS.md for how to add one.
 */
public interface NotificationProvider {

    /** Stable short name stored with each sent message, e.g. "development", "twilio-sms". */
    String name();

    boolean supports(NotificationChannel channel);

    /**
     * Delivers one message. Implementations should pass {@link OutboundMessage#idempotencyKey()}
     * to the vendor when it supports idempotency, because delivery is at-least-once.
     *
     * @return the vendor's message id (for support/tracing)
     * @throws NotificationDeliveryException when the message was not accepted
     */
    String send(OutboundMessage message);
}
