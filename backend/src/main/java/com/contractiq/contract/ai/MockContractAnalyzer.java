package com.contractiq.contract.ai;

import com.contractiq.contract.ai.AnalysisResult.Obligation;
import com.contractiq.contract.ai.AnalysisResult.RiskFlag;
import com.contractiq.contract.ai.AnalysisResult.Severity;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A simple rule-based analyzer that needs no network and no API key. It is used in tests and
 * whenever {@code app.ai.provider} is "mock" (the default). It finds facts with keywords and
 * patterns, so it is far less capable than the model, but it follows the same contract: every
 * risk-flag quote is copied word for word from the text, and it never decides anything.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockContractAnalyzer implements ContractAnalyzer {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\R+");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern BETWEEN = Pattern.compile(
            "\\bbetween\\s+([^,.;\\n]{2,100}?)\\s+and\\s+([^,.;\\n]{2,100}?)(?=[,.;\\n(]|\\s*$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHALL = Pattern.compile("^(.{1,100}?)\\s+shall\\s+", Pattern.CASE_INSENSITIVE);

    private record Rule(Pattern pattern, String clause, Severity severity, String reason) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("unlimited liability", Pattern.CASE_INSENSITIVE), "Unlimited liability",
                    Severity.HIGH, "Liability appears not to be capped."),
            new Rule(Pattern.compile("ignore (?:all )?(?:the )?previous instructions", Pattern.CASE_INSENSITIVE),
                    "Embedded instructions", Severity.HIGH,
                    "The text contains wording that looks like instructions to an AI system. "
                            + "It was treated as contract text only."),
            new Rule(Pattern.compile("auto(?:matic(?:ally)?)?[- ]renew|automatically renews?",
                    Pattern.CASE_INSENSITIVE), "Automatic renewal", Severity.MEDIUM,
                    "The contract may renew without a new decision."),
            new Rule(Pattern.compile("indemnif", Pattern.CASE_INSENSITIVE), "Indemnification", Severity.MEDIUM,
                    "One party may have to cover the other's losses."),
            new Rule(Pattern.compile("penalt(?:y|ies)|liquidated damages", Pattern.CASE_INSENSITIVE), "Penalties",
                    Severity.MEDIUM, "Financial penalties may apply."),
            new Rule(Pattern.compile("terminat\\w* (?:for convenience|without cause)", Pattern.CASE_INSENSITIVE),
                    "Termination without cause", Severity.MEDIUM, "A party may be able to end the contract at will."),
            new Rule(Pattern.compile("exclusiv", Pattern.CASE_INSENSITIVE), "Exclusivity", Severity.LOW,
                    "An exclusivity wording limits choice of other suppliers."));

    @Override
    public AnalysisResult analyze(String contractText) {
        List<String> sentences = sentences(contractText);
        List<String> parties = parties(contractText);
        List<Obligation> obligations = obligations(sentences);
        List<RiskFlag> riskFlags = riskFlags(sentences);
        return new AnalysisResult(
                parties,
                dateInSentenceWith(sentences, "effective"),
                dateInSentenceWith(sentences, "expire", "expiry", "end date", "until", "terminates on"),
                firstSentenceWith(sentences, "renew"),
                firstSentenceWith(sentences, "payment", "invoice"),
                obligations,
                riskFlags,
                "Rule-based mock analysis: " + parties.size() + " parties, " + obligations.size()
                        + " obligations, " + riskFlags.size() + " risk flags.");
    }

    private static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(text == null ? "" : text)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> parties(String text) {
        List<String> parties = new ArrayList<>();
        Matcher matcher = BETWEEN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            parties.add(matcher.group(1).strip());
            parties.add(matcher.group(2).strip());
        }
        return parties;
    }

    private static String dateInSentenceWith(List<String> sentences, String... keywords) {
        for (String sentence : sentences) {
            if (containsAny(sentence, keywords)) {
                String date = firstValidDate(sentence);
                if (date != null) {
                    return date;
                }
            }
        }
        return null;
    }

    private static String firstSentenceWith(List<String> sentences, String... keywords) {
        for (String sentence : sentences) {
            if (containsAny(sentence, keywords)) {
                return truncate(sentence, AnalysisValidator.MAX_LONG_TEXT);
            }
        }
        return null;
    }

    private static List<Obligation> obligations(List<String> sentences) {
        List<Obligation> obligations = new ArrayList<>();
        for (String sentence : sentences) {
            Matcher shall = SHALL.matcher(sentence);
            if (shall.find() && obligations.size() < AnalysisValidator.MAX_OBLIGATIONS) {
                obligations.add(new Obligation(truncate(sentence, AnalysisValidator.MAX_OBLIGATION_TEXT),
                        shall.group(1).strip(), firstValidDate(sentence)));
            }
        }
        return obligations;
    }

    private static List<RiskFlag> riskFlags(List<String> sentences) {
        List<RiskFlag> flags = new ArrayList<>();
        for (Rule rule : RULES) {
            for (String sentence : sentences) {
                if (rule.pattern().matcher(sentence).find()) {
                    // The quote is the sentence itself, cut to length: always a substring of the text.
                    flags.add(new RiskFlag(rule.clause(), rule.severity(), rule.reason(),
                            truncate(sentence, AnalysisValidator.MAX_QUOTE)));
                    break;
                }
            }
        }
        return flags;
    }

    private static String firstValidDate(String sentence) {
        Matcher matcher = ISO_DATE.matcher(sentence);
        while (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1)).toString();
            } catch (DateTimeParseException e) {
                // not a real date such as 2026-02-30: keep looking
            }
        }
        return null;
    }

    private static boolean containsAny(String sentence, String... keywords) {
        String lower = sentence.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).stripTrailing();
    }
}
