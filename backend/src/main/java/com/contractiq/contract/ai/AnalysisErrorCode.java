package com.contractiq.contract.ai;

/**
 * Safe, fixed error codes stored on a contract when its analysis fails. The codes are the only
 * failure detail that reaches clients: raw provider messages never do.
 */
public enum AnalysisErrorCode {
    /** The model's output was unusable even after one corrective retry. */
    ANALYSIS_INVALID_OUTPUT,
    /** The provider answered with an error that retrying will not fix. */
    ANALYSIS_PROVIDER_ERROR,
    /** The provider did not answer in time. */
    ANALYSIS_TIMEOUT,
    /** The contract text is larger than the analyzer accepts. */
    ANALYSIS_TOO_LARGE,
    /** The provider or the job queue was not available. */
    ANALYSIS_UNAVAILABLE,
    /** An unexpected error inside the application. */
    ANALYSIS_INTERNAL_ERROR
}
