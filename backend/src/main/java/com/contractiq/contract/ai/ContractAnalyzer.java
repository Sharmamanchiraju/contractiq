package com.contractiq.contract.ai;

import java.util.List;

/**
 * Turns contract text into an {@link AnalysisResult}. Implementations: a rule-based mock and the
 * Claude client, chosen by {@code app.ai.provider}. The text passed in is already redacted, and
 * the result is validated by the caller, not here.
 */
public interface ContractAnalyzer {

    /**
     * Analyzes the text.
     *
     * @throws InvalidAnalyzerOutputException if the model's output is not usable
     * @throws AnalysisException              if the analysis could not be produced
     */
    AnalysisResult analyze(String contractText);

    /**
     * Analyzes the text again after the previous answer failed validation. {@code problems}
     * lists what was wrong, without any contract text. The default ignores the problems, which is
     * right for analyzers that are deterministic.
     */
    default AnalysisResult analyzeWithCorrection(String contractText, List<String> problems) {
        return analyze(contractText);
    }
}
