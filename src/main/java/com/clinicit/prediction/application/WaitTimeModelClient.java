package com.clinicit.prediction.application;

import com.clinicit.prediction.domain.WaitTimeFeatures;

import java.util.List;

/**
 * Port to the wait-time model. The queue and appointment code never call it: only
 * {@link WaitTimePredictionService} does, and it falls back to the baseline on any failure.
 */
public interface WaitTimeModelClient {

    /** One prediction per feature row, in the same order. */
    ModelResponse predict(List<WaitTimeFeatures> rows);

    /** As returned by the service; validated by the caller before use. */
    record ModelResponse(String schemaVersion, String modelVersion, List<Prediction> predictions) {}

    record Prediction(Double estimatedWaitMinutes, Double lowerBoundMinutes, Double upperBoundMinutes,
                      String source, String reason) {}

    /** The service could not be reached, timed out or answered with an error. */
    class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
