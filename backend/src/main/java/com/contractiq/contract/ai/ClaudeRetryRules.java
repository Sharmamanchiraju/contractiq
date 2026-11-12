package com.contractiq.contract.ai;

import java.time.Duration;

/**
 * Decides which failures are worth retrying and how long to wait. Kept free of any library so it
 * can be tested on its own.
 *
 * <p>Retried: HTTP 429, any 5xx (500, 502, 503, 504 and 529 overloaded) and timeouts.
 * Never retried: any other status (400, 401, 402, 403, 404, 413, ...), a 429 that signals a spend
 * limit, a 429 or 5xx whose retry-after is longer than {@code maxRetryAfter}, and failures that
 * are not HTTP or timeouts (connection refused, circuit open, bulkhead full, invalid output).
 */
final class ClaudeRetryRules {

    private final ClaudeProperties.Retry settings;

    ClaudeRetryRules(ClaudeProperties.Retry settings) {
        this.settings = settings;
    }

    boolean isRetryable(Throwable failure) {
        if (failure instanceof ClaudeTimeoutException) {
            return true;
        }
        if (failure instanceof ClaudeHttpException http) {
            if (http.spendLimit()) {
                return false;
            }
            boolean retryableStatus = http.status() == 429 || (http.status() >= 500 && http.status() <= 599);
            return retryableStatus && retryAfterIsAcceptable(http);
        }
        return false;
    }

    /**
     * Milliseconds to wait before the next try, after attempt number {@code attempt} (1 for the
     * first try) failed. Exponential backoff, capped; never shorter than the provider's retry-after.
     */
    long delayMillis(int attempt, Throwable failure) {
        double exponential = settings.initialBackoff().toMillis() * Math.pow(settings.multiplier(), attempt - 1);
        long computed = (long) Math.min(exponential, settings.maxBackoff().toMillis());
        if (failure instanceof ClaudeHttpException http && http.retryAfterSeconds() != null) {
            return Math.max(computed, Duration.ofSeconds(http.retryAfterSeconds()).toMillis());
        }
        return computed;
    }

    /**
     * Whether a failure should count towards opening the circuit breaker: provider trouble (429,
     * 5xx, timeouts, unreachable), not our own bad requests and not unusable answers.
     */
    boolean countsAsProviderFailure(Throwable failure) {
        if (failure instanceof ClaudeTimeoutException || failure instanceof ClaudeIoException) {
            return true;
        }
        return failure instanceof ClaudeHttpException http
                && (http.status() == 429 || (http.status() >= 500 && http.status() <= 599));
    }

    private boolean retryAfterIsAcceptable(ClaudeHttpException http) {
        return http.retryAfterSeconds() == null
                || Duration.ofSeconds(http.retryAfterSeconds()).compareTo(settings.maxRetryAfter()) <= 0;
    }
}
