package com.contractiq.contract.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Runs one analysis job on the bounded thread pool: load the text, redact it, ask the analyzer,
 * validate (with one corrective retry), then store the result or the failure.
 *
 * <p>The job never holds a database transaction while the analyzer runs. It never decides a
 * contract: the only outcomes it can write are ANALYZED and NEEDS_MANUAL_REVIEW. Logs contain
 * contract ids and error codes only, never contract text, analysis content or provider messages.
 */
@Component
public class AnalysisJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobRunner.class);

    private final AnalysisStateService state;
    private final ContractAnalyzer analyzer;
    private final AnalysisValidator validator;
    private final Redactor redactor;
    private final Executor executor;
    private final AiProperties properties;

    public AnalysisJobRunner(AnalysisStateService state, ContractAnalyzer analyzer, AnalysisValidator validator,
                             Redactor redactor, @Qualifier("analysisExecutor") Executor executor,
                             AiProperties properties) {
        this.state = state;
        this.analyzer = analyzer;
        this.validator = validator;
        this.redactor = redactor;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * Queues the job. If the pool and its queue are full, the contract is marked as unavailable
     * instead of waiting forever or running on the caller's thread. Never throws.
     */
    public void submit(Long contractId, Long expectedVersion) {
        try {
            executor.execute(() -> run(contractId, expectedVersion));
        } catch (RejectedExecutionException e) {
            log.warn("Analysis queue full; contract {} marked unavailable", contractId);
            safeFail(contractId, expectedVersion, AnalysisErrorCode.ANALYSIS_UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("Could not queue analysis for contract {} type={}", contractId, e.getClass().getName());
            safeFail(contractId, expectedVersion, AnalysisErrorCode.ANALYSIS_INTERNAL_ERROR);
        }
    }

    /** The job body. Public so tests can run it directly; normally called by the executor. */
    public void run(Long contractId, Long expectedVersion) {
        try {
            Optional<String> text = state.loadTextForJob(contractId, expectedVersion);
            if (text.isEmpty()) {
                log.info("Analysis job for contract {} skipped: superseded or no longer analyzing", contractId);
                return;
            }
            Outcome outcome = AnalysisCallContext.callWith(contractId, () -> analyze(text.get()));
            if (outcome.result() != null) {
                state.complete(contractId, expectedVersion, outcome.result(), properties.provider());
                log.info("Analysis for contract {} finished", contractId);
            } else {
                state.fail(contractId, expectedVersion, outcome.errorCode());
                log.warn("Analysis for contract {} failed code={}", contractId, outcome.errorCode());
            }
        } catch (RuntimeException e) {
            // Whatever went wrong, do not leave the contract stuck in ANALYZING.
            log.error("Analysis job for contract {} crashed type={}", contractId, e.getClass().getName());
            safeFail(contractId, expectedVersion, AnalysisErrorCode.ANALYSIS_INTERNAL_ERROR);
        }
    }

    private record Outcome(AnalysisResult result, AnalysisErrorCode errorCode) {
    }

    /** Redacts, analyzes and validates; on invalid output retries exactly once with the problems listed. */
    private Outcome analyze(String contractText) {
        // The exact text sent to the analyzer; quotes are checked against this, not the original.
        String sentText = redactor.redact(contractText);
        List<String> problems = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AnalysisResult result = attempt == 0
                        ? analyzer.analyze(sentText)
                        : analyzer.analyzeWithCorrection(sentText, problems);
                problems = validator.validate(result, sentText);
                if (problems.isEmpty()) {
                    return new Outcome(result, null);
                }
                log.info("Analysis output invalid (attempt {}), {} problem(s)", attempt + 1, problems.size());
            } catch (InvalidAnalyzerOutputException e) {
                problems = List.of("The answer was not valid structured data: " + e.getMessage());
                log.info("Analysis output unusable (attempt {})", attempt + 1);
            } catch (AnalysisException e) {
                return new Outcome(null, e.getCode());
            }
        }
        return new Outcome(null, AnalysisErrorCode.ANALYSIS_INVALID_OUTPUT);
    }

    private void safeFail(Long contractId, Long expectedVersion, AnalysisErrorCode code) {
        try {
            state.fail(contractId, expectedVersion, code);
        } catch (RuntimeException e) {
            log.error("Could not record failure for contract {} type={}", contractId, e.getClass().getName());
        }
    }
}
