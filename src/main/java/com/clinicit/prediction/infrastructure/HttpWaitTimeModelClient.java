package com.clinicit.prediction.infrastructure;

import com.clinicit.prediction.application.PredictionProperties;
import com.clinicit.prediction.application.WaitTimeModelClient;
import com.clinicit.prediction.application.WaitTimeModelClient.ModelUnavailableException.Kind;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
            throw new ModelUnavailableException(Kind.DISABLED, "ML service not configured", null);
        }
        try {
            return http.post()
                    .uri(properties.mlBaseUrl() + "/predict/wait-time")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("schemaVersion", SCHEMA_VERSION, "instances", rows))
                    .retrieve()
                    .body(ModelResponse.class);
        } catch (RestClientResponseException e) {
            throw new ModelUnavailableException(Kind.HTTP_ERROR, "ML service answered HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            Kind kind = isTimeout(e) ? Kind.TIMEOUT : Kind.UNREACHABLE;
            throw new ModelUnavailableException(kind, "ML service " + kind.name().toLowerCase(), e);
        } catch (RestClientException | IllegalArgumentException e) {
            throw new ModelUnavailableException(Kind.BAD_RESPONSE, "ML service response unreadable: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
