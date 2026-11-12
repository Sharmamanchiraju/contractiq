package com.contractiq.contract.ai;

/** The provider did not answer in time (connect or read timeout). Retryable. */
final class ClaudeTimeoutException extends RuntimeException {

    ClaudeTimeoutException() {
        super("The AI provider did not answer in time");
    }
}
