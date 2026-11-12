package com.contractiq.contract.ai;

/**
 * A network failure that is not a timeout (for example connection refused). Not retried: the
 * retry rules cover only 429, 5xx and timeouts.
 */
final class ClaudeIoException extends RuntimeException {

    ClaudeIoException() {
        super("The AI provider could not be reached");
    }
}
