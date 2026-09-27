package com.clinicit.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (e.g. session cleanup). Tests switch this off and call the jobs directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "clinicit.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
