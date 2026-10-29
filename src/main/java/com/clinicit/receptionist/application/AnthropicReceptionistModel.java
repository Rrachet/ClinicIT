package com.clinicit.receptionist.application;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Claude through the official Anthropic Java SDK. Built lazily: no key, no client. */
@Component
public class AnthropicReceptionistModel implements ReceptionistModel {

    private final ReceptionistProperties properties;
    private volatile AnthropicClient client;

    public AnthropicReceptionistModel(ReceptionistProperties properties) {
        this.properties = properties;
    }

    @Override
    public BetaMessage create(MessageCreateParams params) {
        return client().beta().messages().create(params);
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    var builder = AnthropicOkHttpClient.builder()
                            .apiKey(properties.apiKey())
                            .timeout(Duration.ofSeconds(60))
                            .maxRetries(2);
                    if (!properties.baseUrl().isEmpty()) {
                        builder.baseUrl(properties.baseUrl());
                    }
                    client = builder.build();
                }
                current = client;
            }
        }
        return current;
    }
}
