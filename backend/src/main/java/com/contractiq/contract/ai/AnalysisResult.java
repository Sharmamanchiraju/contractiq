package com.contractiq.contract.ai;

import java.util.List;

/**
 * The structured facts extracted from one contract. It has no status or decision field on
 * purpose: an analysis can describe a contract but can never approve or reject it.
 * Dates are ISO strings ({@code yyyy-MM-dd}) or null so that a bad date from the model is caught
 * by {@link AnalysisValidator} instead of failing while parsing.
 *
 * @param parties       the parties named in the contract
 * @param effectiveDate when the contract starts, or null if not stated
 * @param expiryDate    when the contract ends, or null if not stated
 * @param renewalTerms  how it renews, or null if not stated
 * @param paymentTerms  how and when payment happens, or null if not stated
 * @param obligations   things a party must do
 * @param riskFlags     clauses a human should look at, each with a verbatim quote
 * @param summary       a short plain-language summary
 */
public record AnalysisResult(
        List<String> parties,
        String effectiveDate,
        String expiryDate,
        String renewalTerms,
        String paymentTerms,
        List<Obligation> obligations,
        List<RiskFlag> riskFlags,
        String summary) {

    /**
     * Something a party must do.
     *
     * @param description what must be done
     * @param party       who must do it, or null if unclear
     * @param dueDate     ISO date it is due, or null
     */
    public record Obligation(String description, String party, String dueDate) {
    }

    /**
     * A clause that deserves a human's attention.
     *
     * @param clause   short name of the clause
     * @param severity how serious it looks
     * @param reason   why it is flagged
     * @param quote    short text copied word for word from the contract, so a human can verify it
     */
    public record RiskFlag(String clause, Severity severity, String reason, String quote) {
    }

    /** How serious a risk flag is. */
    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
