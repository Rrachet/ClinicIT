package com.clinicit.prediction.infrastructure;

import com.clinicit.prediction.application.PredictionProperties;
import com.clinicit.prediction.application.WaitTimeModelClient;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/**
 * Calls {@code POST {mlBaseUrl}/predict/wait-time} (request/response schema version "1",
 * see ml/README.md). Only features are sent: no clinic, doctor or patient identifiers.
 * Every failure, including a timeout, becomes {@link ModelUnavailableException}.
 */
@Component
public class HttpWaitTimeModelClient implements WaitTimeModelClient {

    public static final String SCHEMA_VERSION = "1";

    private final RestClient http;
    private final PredictionProperties properties;

    public HttpWaitTimeModelClient(PredictionProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.timeout());
        this.http = builder.clone().requestFactory(factory).build();
    }

    @Override
    public ModelResponse predict(List<WaitTimeFeatures> rows) {
        if (!properties.mlEnabled()) {
            throw new ModelUnavailableException("ML service not configured", null);
        }
        try {
            return http.post()
                    .uri(properties.mlBaseUrl() + "/predict/wait-time")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("schemaVersion", SCHEMA_VERSION, "instances", rows))
                    .retrieve()
                    .body(ModelResponse.class);
        } catch (RestClientException | IllegalArgumentException e) {
            throw new ModelUnavailableException("ML service call failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
