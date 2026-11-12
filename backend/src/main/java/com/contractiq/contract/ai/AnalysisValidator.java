package com.contractiq.contract.ai;

import com.contractiq.contract.ai.AnalysisResult.Obligation;
import com.contractiq.contract.ai.AnalysisResult.RiskFlag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks every field of an {@link AnalysisResult} before it is stored or shown. Model output is
 * untrusted: it may have bad dates, missing fields, oversized lists or made-up quotes.
 *
 * <p>Risk-flag quotes are checked against the exact text that was sent to the analyzer (the
 * redacted text, not the original) after collapsing whitespace, so a quote that contains a
 * placeholder such as {@code [REDACTED_EMAIL]} is accepted, and one that contains the raw value is not.
 *
 * <p>Problem messages name the field and the rule only. They never contain contract text, so they
 * are safe to log and to send back to the model as a correction.
 */
@Component
public class AnalysisValidator {

    static final int MAX_PARTIES = 20;
    static final int MAX_OBLIGATIONS = 50;
    static final int MAX_RISK_FLAGS = 30;
    static final int MAX_SHORT_TEXT = 300;
    static final int MAX_LONG_TEXT = 2000;
    static final int MAX_OBLIGATION_TEXT = 1000;
    static final int MAX_QUOTE = 500;

    /**
     * Returns the list of problems found; an empty list means the result is valid.
     *
     * @param result       the parsed analysis
     * @param analyzedText the exact text the analyzer was given (already redacted)
     */
    public List<String> validate(AnalysisResult result, String analyzedText) {
        List<String> problems = new ArrayList<>();
        if (result == null) {
            problems.add("The analysis is missing.");
            return problems;
        }
        requireText(problems, "summary", result.summary(), MAX_LONG_TEXT);
        validateParties(problems, result.parties());
        LocalDate effective = validateDate(problems, "effectiveDate", result.effectiveDate());
        LocalDate expiry = validateDate(problems, "expiryDate", result.expiryDate());
        if (effective != null && expiry != null && expiry.isBefore(effective)) {
            problems.add("expiryDate must not be before effectiveDate.");
        }
        optionalText(problems, "renewalTerms", result.renewalTerms(), MAX_LONG_TEXT);
        optionalText(problems, "paymentTerms", result.paymentTerms(), MAX_LONG_TEXT);
        validateObligations(problems, result.obligations());
        validateRiskFlags(problems, result.riskFlags(), analyzedText);
        return problems;
    }

    private void validateParties(List<String> problems, List<String> parties) {
        if (parties == null) {
            problems.add("parties is required (use an empty list if none are named).");
            return;
        }
        if (parties.size() > MAX_PARTIES) {
            problems.add("parties has more than " + MAX_PARTIES + " entries.");
        }
        for (int i = 0; i < Math.min(parties.size(), MAX_PARTIES); i++) {
            requireText(problems, "parties[" + i + "]", parties.get(i), MAX_SHORT_TEXT);
        }
    }

    private void validateObligations(List<String> problems, List<Obligation> obligations) {
        if (obligations == null) {
            problems.add("obligations is required (use an empty list if none).");
            return;
        }
        if (obligations.size() > MAX_OBLIGATIONS) {
            problems.add("obligations has more than " + MAX_OBLIGATIONS + " entries.");
        }
        for (int i = 0; i < Math.min(obligations.size(), MAX_OBLIGATIONS); i++) {
            Obligation obligation = obligations.get(i);
            String at = "obligations[" + i + "]";
            if (obligation == null) {
                problems.add(at + " is empty.");
                continue;
            }
            requireText(problems, at + ".description", obligation.description(), MAX_OBLIGATION_TEXT);
            optionalText(problems, at + ".party", obligation.party(), MAX_SHORT_TEXT);
            validateDate(problems, at + ".dueDate", obligation.dueDate());
        }
    }

    private void validateRiskFlags(List<String> problems, List<RiskFlag> flags, String analyzedText) {
        if (flags == null) {
            problems.add("riskFlags is required (use an empty list if none).");
            return;
        }
        if (flags.size() > MAX_RISK_FLAGS) {
            problems.add("riskFlags has more than " + MAX_RISK_FLAGS + " entries.");
        }
        String normalizedText = normalize(analyzedText == null ? "" : analyzedText);
        for (int i = 0; i < Math.min(flags.size(), MAX_RISK_FLAGS); i++) {
            RiskFlag flag = flags.get(i);
            String at = "riskFlags[" + i + "]";
            if (flag == null) {
                problems.add(at + " is empty.");
                continue;
            }
            requireText(problems, at + ".clause", flag.clause(), MAX_SHORT_TEXT);
            requireText(problems, at + ".reason", flag.reason(), MAX_OBLIGATION_TEXT);
            if (flag.severity() == null) {
                problems.add(at + ".severity is required (LOW, MEDIUM or HIGH).");
            }
            if (requireText(problems, at + ".quote", flag.quote(), MAX_QUOTE)
                    && !normalizedText.contains(normalize(flag.quote()))) {
                problems.add(at + ".quote does not appear word for word in the contract text.");
            }
        }
    }

    /** Returns the date, or null if absent or invalid (an invalid one is added to problems). */
    private LocalDate validateDate(List<String> problems, String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            problems.add(field + " must be a real date in yyyy-MM-dd format, or null.");
            return null;
        }
    }

    /** Returns true if the value is present and acceptable; adds a problem and returns false otherwise. */
    private boolean requireText(List<String> problems, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            problems.add(field + " is required and must not be blank.");
            return false;
        }
        if (value.length() > maxLength) {
            problems.add(field + " is longer than " + maxLength + " characters.");
            return false;
        }
        return true;
    }

    private void optionalText(List<String> problems, String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            problems.add(field + " is longer than " + maxLength + " characters.");
        }
    }

    /** Collapses every run of whitespace to one space and trims, so line breaks do not matter. */
    static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
