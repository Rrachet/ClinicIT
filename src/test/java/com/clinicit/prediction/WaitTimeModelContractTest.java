package com.clinicit.prediction;

import com.clinicit.prediction.application.WaitTimeModelClient.ModelResponse;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import com.clinicit.prediction.infrastructure.HttpWaitTimeModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request Spring Boot sends has exactly the fields of the shared schema-1 example that
 * the Python service's tests accept (ml/contracts/predict_request_v1.json), and a schema-1
 * response parses.
 */
class WaitTimeModelContractTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void theRequestMatchesTheSharedExample() throws Exception {
        JsonNode example = json.readTree(Path.of("ml/contracts/predict_request_v1.json").toFile()).get("request");
        WaitTimeFeatures features = new WaitTimeFeatures(2, 605, 2, 3, 4, 1, 5, 3, 11.5, 10.2, 140, 24.0);

        JsonNode sent = json.valueToTree(Map.of("schemaVersion", HttpWaitTimeModelClient.SCHEMA_VERSION,
                "instances", List.of(features)));

        assertThat(fields(sent)).isEqualTo(fields(example));
        assertThat(fields(sent.get("instances").get(0))).isEqualTo(fields(example.get("instances").get(0)));
        assertThat(sent.get("instances").get(0)).isEqualTo(example.get("instances").get(0));
        assertThat(sent.get("schemaVersion").asText()).isEqualTo("1");
    }

    @Test
    void aSchemaOneResponseParses() throws Exception {
        ModelResponse response = json.readValue("""
                {"schemaVersion":"1","modelVersion":"wait-random-forest-abc",
                 "predictions":[{"estimatedWaitMinutes":23.4,"lowerBoundMinutes":17.0,"upperBoundMinutes":31.2,
                                 "source":"MODEL","reason":null}]}""", ModelResponse.class);

        assertThat(response.modelVersion()).isEqualTo("wait-random-forest-abc");
        assertThat(response.predictions()).singleElement().satisfies(p -> {
            assertThat(p.estimatedWaitMinutes()).isEqualTo(23.4);
            assertThat(p.source()).isEqualTo("MODEL");
        });
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
