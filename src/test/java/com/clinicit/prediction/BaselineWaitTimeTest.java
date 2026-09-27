package com.clinicit.prediction;

import com.clinicit.prediction.application.BaselineWaitTime;
import com.clinicit.prediction.domain.WaitTimeEstimate;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The Java baseline matches the shared vectors the Python baseline is also tested against. */
class BaselineWaitTimeTest {

    @Test
    void matchesTheSharedContractCases() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Path.of("ml/contracts/baseline_cases.json").toFile()).get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(6);

        for (JsonNode c : cases) {
            JsonNode f = c.get("features");
            JsonNode expected = c.get("expected");
            WaitTimeFeatures features = new WaitTimeFeatures(2, 600, f.get("patientsAhead").asInt(), 0, 0, 0, 0, 0,
                    f.get("recentConsultationMinutes").isNull() ? null : f.get("recentConsultationMinutes").asDouble(),
                    f.get("historicalConsultationMinutes").isNull() ? null : f.get("historicalConsultationMinutes").asDouble(),
                    0, null);

            WaitTimeEstimate estimate = BaselineWaitTime.estimate(features, "TEST");

            assertThat(BaselineWaitTime.minutes(features)).as(c.get("name").asText())
                    .isCloseTo(expected.get("minutes").asDouble(), within(1e-9));
            assertThat(estimate.estimatedWaitMinutes()).as(c.get("name").asText()).isEqualTo(expected.get("estimate").asInt());
            assertThat(estimate.lowerBoundMinutes()).as(c.get("name").asText()).isEqualTo(expected.get("lower").asInt());
            assertThat(estimate.upperBoundMinutes()).as(c.get("name").asText()).isEqualTo(expected.get("upper").asInt());
            assertThat(estimate.source()).isEqualTo(WaitTimeEstimate.Source.BASELINE);
            assertThat(estimate.modelVersion()).isEqualTo("baseline-v1");
        }
    }
}
