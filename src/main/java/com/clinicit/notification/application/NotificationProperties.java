package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationChannel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * @param enabled            master switch; when false nothing is created or sent
 * @param channel            channel used for patients (they have a phone number)
 * @param statusLinkBaseUrl  public URL of the frontend; links are {base}/status/{code}
 * @param nearTurnThreshold  "nearly your turn" once at most this many patients are ahead
 * @param maxAttempts        delivery attempts before a message is marked FAILED
 * @param retryBaseDelay     first retry delay; doubles each attempt
 * @param retryMaxDelay      cap for the retry delay
 * @param asyncDelivery      send on a background thread after commit (false in tests)
 * @param retention          how long delivered/failed rows (which hold phone numbers) are kept
 */
@ConfigurationProperties("clinicit.notifications")
public record NotificationProperties(
        Boolean enabled,
        NotificationChannel channel,
        String statusLinkBaseUrl,
        Integer nearTurnThreshold,
        Integer maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Boolean asyncDelivery,
        Duration retention
) {
    public NotificationProperties {
        enabled = enabled == null || enabled;
        channel = channel == null ? NotificationChannel.SMS : channel;
        statusLinkBaseUrl = normalizeBase(statusLinkBaseUrl == null ? "http://localhost:3000" : statusLinkBaseUrl);
        nearTurnThreshold = nearTurnThreshold == null ? 1 : nearTurnThreshold;
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofSeconds(30) : retryBaseDelay;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofMinutes(30) : retryMaxDelay;
        asyncDelivery = asyncDelivery == null || asyncDelivery;
        retention = retention == null ? Duration.ofDays(30) : retention;
        if (nearTurnThreshold < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("clinicit.notifications thresholds must be positive");
        }
    }

    public String statusLink(String statusCode) {
        return statusLinkBaseUrl + "/status/" + statusCode;
    }

    private static String normalizeBase(String base) {
        URI uri = URI.create(base.trim());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getQuery() != null) {
            throw new IllegalArgumentException("clinicit.notifications.status-link-base-url must be an http(s) URL");
        }
        return base.trim().replaceAll("/+$", "");
    }
}
