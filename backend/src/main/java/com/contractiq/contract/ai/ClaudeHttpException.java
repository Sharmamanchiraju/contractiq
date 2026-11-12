package com.contractiq.contract.ai;

/**
 * The provider answered with a non-success HTTP status. Carries only the status and two facts the
 * retry rules need. The response body is never kept, so provider messages cannot leak.
 */
final class ClaudeHttpException extends RuntimeException {

    private final int status;
    private final Long retryAfterSeconds;
    private final boolean spendLimit;

    ClaudeHttpException(int status, Long retryAfterSeconds, boolean spendLimit) {
        super("The AI provider answered with HTTP " + status);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.spendLimit = spendLimit;
    }

    int status() {
        return status;
    }

    /** Seconds the provider asked us to wait, or null if it sent no usable retry-after header. */
    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** True for a 429 that signals a spend limit, which waiting will not fix. */
    boolean spendLimit() {
        return spendLimit;
    }
}
