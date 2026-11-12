package com.contractiq.contract.ai;

/**
 * Thrown by an analyzer when the model answered but the answer is not usable structured data
 * (no tool call, wrong stop reason, unparseable JSON). The service retries once with a corrective
 * message. The message must be safe to log: no contract text.
 */
public class InvalidAnalyzerOutputException extends RuntimeException {

    public InvalidAnalyzerOutputException(String safeReason) {
        super(safeReason);
    }
}
