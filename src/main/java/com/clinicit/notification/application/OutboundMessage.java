package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationChannel;

/** Exactly what a provider needs: where, what, and a key to deduplicate retries. */
public record OutboundMessage(String idempotencyKey, NotificationChannel channel, String recipient, String body) {

    @Override
    public String toString() {
        // Never log phone numbers or message links.
        return "OutboundMessage[key=" + idempotencyKey + ", channel=" + channel + "]";
    }
}
