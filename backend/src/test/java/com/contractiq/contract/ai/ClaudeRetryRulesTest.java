package com.contractiq.contract.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the retry rules: exactly which failures are retried, how long to wait, and which
 * failures count towards opening the circuit breaker.
 */
class ClaudeRetryRulesTest {

    private final ClaudeProperties.Retry settings = new ClaudeProperties.Retry(
            3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), Duration.ofSeconds(30));
    private final ClaudeRetryRules rules = new ClaudeRetryRules(settings);

    private static ClaudeHttpException http(int status) {
        return new ClaudeHttpException(status, null, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 501, 502, 503, 504, 529, 599})
    void rateLimitsAndServerErrorsAreRetried(int status) {
        assertThat(rules.isRetryable(http(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404, 405, 409, 413, 422})
    void otherClientErrorsAreNeverRetried(int status) {
        assertThat(rules.isRetryable(http(status))).isFalse();
    }

    @Test
    void timeoutsAreRetried() {
        assertThat(rules.isRetryable(new ClaudeTimeoutException())).isTrue();
    }

    @Test
    void aSpendLimitRateLimitIsNeverRetried() {
        assertThat(rules.isRetryable(new ClaudeHttpException(429, null, true))).isFalse();
    }

    @Test
    void aRetryAfterWithinTheCapIsRetriedAndOneBeyondItIsNot() {
        assertThat(rules.isRetryable(new ClaudeHttpException(429, 30L, false))).isTrue();
        assertThat(rules.isRetryable(new ClaudeHttpException(429, 31L, false))).isFalse();
        assertThat(rules.isRetryable(new ClaudeHttpException(503, 300L, false))).isFalse();
    }

    @Test
    void connectionFailuresAndOtherExceptionsAreNotRetried() {
        assertThat(rules.isRetryable(new ClaudeIoException())).isFalse();
        assertThat(rules.isRetryable(new InvalidAnalyzerOutputException("bad"))).isFalse();
        assertThat(rules.isRetryable(new IllegalStateException("x"))).isFalse();
        assertThat(rules.isRetryable(new AnalysisException(AnalysisErrorCode.ANALYSIS_TIMEOUT, "x"))).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(rules.delayMillis(1, http(500))).isEqualTo(1_000);
        assertThat(rules.delayMillis(2, http(500))).isEqualTo(2_000);
        assertThat(rules.delayMillis(3, http(500))).isEqualTo(4_000);
        assertThat(rules.delayMillis(4, http(500))).isEqualTo(8_000);
        assertThat(rules.delayMillis(5, http(500))).isEqualTo(10_000);
        assertThat(rules.delayMillis(9, new ClaudeTimeoutException())).isEqualTo(10_000);
    }

    @Test
    void theWaitIsNeverShorterThanRetryAfter() {
        assertThat(rules.delayMillis(1, new ClaudeHttpException(429, 7L, false))).isEqualTo(7_000);
        // The computed backoff wins when it is already longer.
        assertThat(rules.delayMillis(4, new ClaudeHttpException(429, 2L, false))).isEqualTo(8_000);
        // A retry-after longer than the backoff cap is honored (it is within the give-up cap).
        assertThat(rules.delayMillis(1, new ClaudeHttpException(429, 25L, false))).isEqualTo(25_000);
    }

    @Test
    void onlyProviderTroubleCountsTowardsTheCircuitBreaker() {
        assertThat(rules.countsAsProviderFailure(http(500))).isTrue();
        assertThat(rules.countsAsProviderFailure(http(529))).isTrue();
        assertThat(rules.countsAsProviderFailure(http(429))).isTrue();
        assertThat(rules.countsAsProviderFailure(new ClaudeTimeoutException())).isTrue();
        assertThat(rules.countsAsProviderFailure(new ClaudeIoException())).isTrue();
        assertThat(rules.countsAsProviderFailure(http(400))).isFalse();
        assertThat(rules.countsAsProviderFailure(http(401))).isFalse();
        assertThat(rules.countsAsProviderFailure(new InvalidAnalyzerOutputException("bad"))).isFalse();
    }

    @Test
    void theHttpExceptionNeverCarriesAResponseBody() {
        ClaudeHttpException exception = new ClaudeHttpException(500, null, false);

        assertThat(exception.getMessage()).isEqualTo("The AI provider answered with HTTP 500");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void anUnknownProviderNameFailsAtStartup() {
        assertThatThrownBy(() -> new AiProperties("claud", Duration.ofMinutes(10),
                new AiProperties.Redaction(true), new AiProperties.Executor(2, 4, 25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mock' or 'claude");
    }

    @Test
    void claudeModeWithoutAnApiKeyFailsAtStartup() {
        ClaudeProperties noKey = new ClaudeProperties("", "m", "http://x", "v", "", 10, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 10, settings, new ClaudeProperties.CircuitBreaker(10, 5, 50, Duration.ofSeconds(1)),
                new ClaudeProperties.Bulkhead(1));

        assertThatThrownBy(() -> ClaudeAiConfig.requireKey(noKey)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void thePropertiesNeverPrintTheApiKey() {
        ClaudeProperties withKey = new ClaudeProperties("sk-ant-SECRET-KEY-VALUE", "m", "http://x", "v", "", 10,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 10, settings,
                new ClaudeProperties.CircuitBreaker(10, 5, 50, Duration.ofSeconds(1)), new ClaudeProperties.Bulkhead(1));

        assertThat(withKey.toString()).doesNotContain("SECRET-KEY-VALUE").contains("<hidden>");
    }
}
