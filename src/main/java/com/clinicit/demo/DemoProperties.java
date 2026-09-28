package com.clinicit.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demo data for evaluation (docs/DEMO.md). Off unless enabled; refused with the prod profile.
 *
 * @param enabled     load the demo clinic on an empty database at startup
 * @param password    the password of every demo login; required (12+ characters) when enabled,
 *                    never defaulted in code
 * @param historyDays past clinic days of simulated history for trends and wait estimates
 */
@ConfigurationProperties("clinicit.demo")
public record DemoProperties(boolean enabled, String password, Integer historyDays) {
    public DemoProperties {
        historyDays = historyDays == null ? 14 : historyDays;
    }
}
