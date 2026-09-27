package com.clinicit.prediction.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param mlBaseUrl       the Python ML service, e.g. http://localhost:8000; empty disables it
 *                        (every estimate then comes from the baseline)
 * @param timeout         whole-request budget for one call to the ML service; kept short
 *                        because an estimate is never worth a slow screen
 * @param failureBackoff  after a failed call, how long to use the baseline without trying the
 *                        service again (so an outage costs one timeout, not one per request)
 * @param cacheTtl        how long an estimate is reused while the doctor's queue is unchanged
 * @param datasetExportPath when set, the app writes the training dataset to this file and exits
 */
@ConfigurationProperties("clinicit.prediction")
public record PredictionProperties(
        String mlBaseUrl,
        Duration timeout,
        Duration failureBackoff,
        Duration cacheTtl,
        String datasetExportPath
) {
    public PredictionProperties {
        mlBaseUrl = mlBaseUrl == null || mlBaseUrl.isBlank() ? null : mlBaseUrl.replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofMillis(800) : timeout;
        failureBackoff = failureBackoff == null ? Duration.ofSeconds(30) : failureBackoff;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(60) : cacheTtl;
    }

    public boolean mlEnabled() {
        return mlBaseUrl != null;
    }
}
