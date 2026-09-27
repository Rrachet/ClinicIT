package com.clinicit.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** Single source of "now" so date-sensitive rules (today's queue) are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
