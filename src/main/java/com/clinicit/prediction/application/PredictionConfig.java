package com.clinicit.prediction.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PredictionProperties.class)
class PredictionConfig {
}
