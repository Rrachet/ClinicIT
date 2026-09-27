package com.clinicit.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void aPatientsStatusCodeIsMaskedInErrorLogs() {
        assertThat(GlobalExceptionHandler.loggablePath("/api/v1/public/queue-status/Abc123-secret_code"))
                .isEqualTo("/api/v1/public/queue-status/***");
        assertThat(GlobalExceptionHandler.loggablePath("/api/v1/queue-entries/5b1c")).isEqualTo("/api/v1/queue-entries/5b1c");
        assertThat(GlobalExceptionHandler.loggablePath(null)).isNull();
    }
}
