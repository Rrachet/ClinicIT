package com.clinicit.prediction.application;

import com.clinicit.prediction.domain.WaitTimeEstimate;
import com.clinicit.prediction.domain.WaitTimeFeatures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaitTimePredictionServiceTest {

    private static final WaitTimeFeatures TWO_AHEAD =
            new WaitTimeFeatures(2, 600, 2, 3, 1, 1, 4, 2, 12.0, 10.0, 40, 20.0);

    @Test
    void withoutAConfiguredServiceTheModelIsNeverCalled() {
        WaitTimeModelClient failIfCalled = rows -> {
            throw new AssertionError("the ML client must not be called when it is disabled");
        };
        var service = new WaitTimePredictionService(null, null, null, null, failIfCalled,
                new PredictionProperties(null, null, null, null, null), null);

        List<WaitTimeEstimate> estimates = service.predict(List.of(TWO_AHEAD), Instant.now());

        assertThat(estimates).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo(WaitTimeEstimate.Source.BASELINE);
            assertThat(e.reason()).isEqualTo("ML_DISABLED");
            assertThat(e.estimatedWaitMinutes()).isEqualTo(24);
        });
    }

    @Test
    void propertiesDefaultToShortTimeoutsAndTrimTheUrl() {
        var properties = new PredictionProperties("http://ml:8000/", null, null, null, null);
        assertThat(properties.mlBaseUrl()).isEqualTo("http://ml:8000");
        assertThat(properties.timeout().toMillis()).isLessThanOrEqualTo(1000);
        assertThat(new PredictionProperties("  ", null, null, null, null).mlEnabled()).isFalse();
    }
}
