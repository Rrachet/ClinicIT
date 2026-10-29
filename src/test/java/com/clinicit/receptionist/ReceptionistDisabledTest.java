package com.clinicit.receptionist;

import com.clinicit.receptionist.application.ReceptionistProperties;
import com.clinicit.receptionist.application.ReceptionistService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Without an Anthropic API key the receptionist is off and never calls Claude. */
class ReceptionistDisabledTest {

    @Test
    void noApiKeyMeansNoReceptionist() {
        ReceptionistProperties properties = new ReceptionistProperties(
                " ", null, null, null, null, null, null, null, null, null);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.model()).isEqualTo("claude-opus-5-5");
        assertThat(properties.effort()).isEqualTo("low");

        ReceptionistService service = new ReceptionistService(properties, params -> {
            throw new AssertionError("Claude must not be called");
        }, null, null, null, null);
        assertThatThrownBy(() -> service.chat("anycode0000", null, "hello"))
                .isInstanceOf(ReceptionistService.UnavailableException.class);
    }
}
