package com.contractiq.contract.ai;

/**
 * Thrown by an analyzer when the analysis cannot be produced. Carries a safe error code. The
 * message must never contain contract text, API keys or raw provider messages.
 */
public class AnalysisException extends RuntimeException {

    private final AnalysisErrorCode code;

    public AnalysisException(AnalysisErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public AnalysisException(AnalysisErrorCode code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public AnalysisErrorCode getCode() {
        return code;
    }
}
