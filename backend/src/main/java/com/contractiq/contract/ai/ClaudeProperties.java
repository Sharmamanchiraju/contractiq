package com.contractiq.contract.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Claude client, bound from {@code app.ai.claude.*}. The API key comes from the
 * ANTHROPIC_API_KEY environment variable and the model from CLAUDE_MODEL. {@link #toString()}
 * hides the key so it cannot leak through logging.
 *
 * @param apiKey           sent as the x-api-key header; never logged
 * @param model            model id, from configuration, never hardcoded in code
 * @param baseUrl          API base URL (overridden in tests)
 * @param anthropicVersion value of the required anthropic-version header
 * @param workspaceId      optional anthropic-workspace-id header; blank means not sent
 * @param maxTokens        output token limit per call
 * @param connectTimeout   how long to wait to connect
 * @param readTimeout      how long to wait for the answer
 * @param maxInputChars    largest text (after redaction) that will be sent
 * @param retry            retry rules
 * @param circuitBreaker   circuit breaker settings
 * @param bulkhead         concurrency limit
 */
@ConfigurationProperties(prefix = "app.ai.claude")
public record ClaudeProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-sonnet-5-5") String model,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("2026-10-21") String anthropicVersion,
        @DefaultValue("") String workspaceId,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("400000") int maxInputChars,
        @DefaultValue Retry retry,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Bulkhead bulkhead) {

    /**
     * @param maxAttempts    total tries including the first
     * @param initialBackoff wait before the first retry
     * @param multiplier     each further wait is this many times longer
     * @param maxBackoff     longest computed wait
     * @param maxRetryAfter  if the provider asks for a longer wait than this, give up instead of retrying
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("10s") Duration maxBackoff,
            @DefaultValue("30s") Duration maxRetryAfter) {
    }

    /**
     * @param slidingWindowSize    number of recent calls considered
     * @param minimumCalls         calls needed before the failure rate is judged
     * @param failureRateThreshold percentage of failures that opens the circuit
     * @param openStateWait        how long the circuit stays open before probing again
     */
    public record CircuitBreaker(
            @DefaultValue("10") int slidingWindowSize,
            @DefaultValue("5") int minimumCalls,
            @DefaultValue("50") int failureRateThreshold,
            @DefaultValue("30s") Duration openStateWait) {
    }

    /** @param maxConcurrentCalls most simultaneous calls to the provider; more are refused at once */
    public record Bulkhead(@DefaultValue("4") int maxConcurrentCalls) {
    }

    @Override
    public String toString() {
        return "ClaudeProperties[model=" + model + ", baseUrl=" + baseUrl + ", anthropicVersion=" + anthropicVersion
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<not set>" : "<hidden>") + "]";
    }
}
