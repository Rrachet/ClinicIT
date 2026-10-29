package com.clinicit.receptionist.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The AI receptionist (docs/AI_RECEPTIONIST.md). Off unless an Anthropic API key is set.
 *
 * @param apiKey           Anthropic API key (ANTHROPIC_API_KEY); blank turns the receptionist off
 * @param baseUrl          API base URL override (tests); blank uses the Anthropic API
 * @param model            Claude model id
 * @param effort           low, medium, high...: low keeps a chat quick and inexpensive
 * @param maxTokens        output cap per model call (thinking included)
 * @param fallbacks        retry a declined request on Anthropic's recommended fallback model
 * @param maxUserTurns     messages a patient may send in one conversation
 * @param maxMessageChars  longest message a patient may send
 * @param maxToolRounds    tool calls the model may chain before answering
 * @param retention        conversations (which hold names and phone numbers) are deleted after this
 */
@ConfigurationProperties("clinicit.receptionist")
public record ReceptionistProperties(
        String apiKey,
        String baseUrl,
        String model,
        String effort,
        Long maxTokens,
        Boolean fallbacks,
        Integer maxUserTurns,
        Integer maxMessageChars,
        Integer maxToolRounds,
        Duration retention
) {
    public ReceptionistProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        model = model == null || model.isBlank() ? "claude-opus-5-5" : model.strip();
        effort = effort == null || effort.isBlank() ? "low" : effort.strip();
        maxTokens = maxTokens == null ? 8000L : maxTokens;
        fallbacks = fallbacks == null || fallbacks;
        maxUserTurns = maxUserTurns == null ? 30 : maxUserTurns;
        maxMessageChars = maxMessageChars == null ? 500 : maxMessageChars;
        maxToolRounds = maxToolRounds == null ? 6 : maxToolRounds;
        retention = retention == null ? Duration.ofDays(1) : retention;
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }
}
