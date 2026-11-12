package com.contractiq.contract.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.contractiq.contract.ai.AnalysisResult.Obligation;
import com.contractiq.contract.ai.AnalysisResult.RiskFlag;
import com.contractiq.contract.ai.AnalysisResult.Severity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AnalysisValidator}: every rule has a passing and a failing case, and the
 * quote check works on the redacted text with whitespace normalized.
 */
class AnalysisValidatorTest {

    private static final String TEXT = "Acme Ltd shall deliver goods.\nLiability is unlimited liability for the vendor.";

    private final AnalysisValidator validator = new AnalysisValidator();

    private static AnalysisResult valid() {
        return new AnalysisResult(List.of("Acme Ltd", "City Council"), "2026-01-01", "2027-01-01",
                "Renews yearly.", "Net 30.",
                List.of(new Obligation("Acme shall deliver goods.", "Acme Ltd", "2026-03-01")),
                List.of(new RiskFlag("Liability", Severity.HIGH, "Uncapped.", "unlimited liability")),
                "A supply contract.");
    }

    private static AnalysisResult with(String effective, String expiry, List<String> parties,
                                       List<Obligation> obligations, List<RiskFlag> flags, String summary) {
        return new AnalysisResult(parties, effective, expiry, null, null, obligations, flags, summary);
    }

    private List<String> problems(AnalysisResult result) {
        return validator.validate(result, TEXT);
    }

    @Test
    void aCompleteResultIsValid() {
        assertThat(problems(valid())).isEmpty();
    }

    @Test
    void anAllEmptyResultWithASummaryIsValid() {
        assertThat(problems(with(null, null, List.of(), List.of(), List.of(), "Nothing found."))).isEmpty();
    }

    @Test
    void aMissingResultIsInvalid() {
        assertThat(validator.validate(null, TEXT)).hasSize(1);
    }

    @Test
    void requiredFieldsMustBePresent() {
        AnalysisResult result = with(null, null, null, null, null, " ");

        assertThat(problems(result)).contains(
                "summary is required and must not be blank.",
                "parties is required (use an empty list if none are named).",
                "obligations is required (use an empty list if none).",
                "riskFlags is required (use an empty list if none).");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2026-13-01", "12/05/2026", "2026-1-5", "tomorrow", "20260101", ""})
    void datesMustBeRealIsoDates(String badDate) {
        assertThat(problems(with(badDate, null, List.of(), List.of(), List.of(), "ok")))
                .contains("effectiveDate must be a real date in yyyy-MM-dd format, or null.");
        assertThat(problems(with(null, badDate, List.of(), List.of(), List.of(), "ok")))
                .contains("expiryDate must be a real date in yyyy-MM-dd format, or null.");
    }

    @Test
    void aLeapDayIsAccepted() {
        assertThat(problems(with("2028-02-29", null, List.of(), List.of(), List.of(), "ok"))).isEmpty();
    }

    @Test
    void expiryMustNotBeBeforeEffective() {
        assertThat(problems(with("2027-01-01", "2026-01-01", List.of(), List.of(), List.of(), "ok")))
                .containsExactly("expiryDate must not be before effectiveDate.");
        assertThat(problems(with("2026-01-01", "2026-01-01", List.of(), List.of(), List.of(), "ok"))).isEmpty();
    }

    @Test
    void obligationDatesAndDescriptionsAreChecked() {
        List<Obligation> obligations = List.of(
                new Obligation("", "Acme", null),
                new Obligation("Do it.", "Acme", "31/12/2026"));

        assertThat(problems(with(null, null, List.of(), obligations, List.of(), "ok"))).containsExactly(
                "obligations[0].description is required and must not be blank.",
                "obligations[1].dueDate must be a real date in yyyy-MM-dd format, or null.");
    }

    @Test
    void severityIsRequired() {
        List<RiskFlag> flags = List.of(new RiskFlag("Liability", null, "Uncapped.", "unlimited liability"));

        assertThat(problems(with(null, null, List.of(), List.of(), flags, "ok")))
                .containsExactly("riskFlags[0].severity is required (LOW, MEDIUM or HIGH).");
    }

    @Test
    void everyRiskFlagFieldIsRequired() {
        List<RiskFlag> flags = List.of(new RiskFlag(null, Severity.LOW, " ", null));

        assertThat(problems(with(null, null, List.of(), List.of(), flags, "ok"))).containsExactly(
                "riskFlags[0].clause is required and must not be blank.",
                "riskFlags[0].reason is required and must not be blank.",
                "riskFlags[0].quote is required and must not be blank.");
    }

    @Test
    void aQuoteMustAppearWordForWordInTheText() {
        List<RiskFlag> flags = List.of(new RiskFlag("Liability", Severity.HIGH, "Uncapped.", "limited liability cap"));

        assertThat(problems(with(null, null, List.of(), List.of(), flags, "ok")))
                .containsExactly("riskFlags[0].quote does not appear word for word in the contract text.");
    }

    @Test
    void quoteMatchingIgnoresLineBreaksAndRepeatedSpaces() {
        // In the text, a line break sits between "goods." and "Liability".
        List<RiskFlag> flags = List.of(new RiskFlag("Delivery", Severity.LOW, "Reason.",
                "deliver   goods.  Liability is\n unlimited liability"));

        assertThat(problems(with(null, null, List.of(), List.of(), flags, "ok"))).isEmpty();
    }

    @Test
    void quoteMatchingIsCaseSensitive() {
        List<RiskFlag> flags = List.of(new RiskFlag("Liability", Severity.HIGH, "Uncapped.", "UNLIMITED LIABILITY"));

        assertThat(problems(with(null, null, List.of(), List.of(), flags, "ok"))).hasSize(1);
    }

    @Test
    void quotesAreCheckedAgainstTheRedactedTextNotTheOriginal() {
        String original = "Send notices to jane@corp.example without delay.";
        String sent = new Redactor(true).redact(original);
        RiskFlag withPlaceholder = new RiskFlag("Notices", Severity.LOW, "Reason.",
                "Send notices to [REDACTED_EMAIL] without delay.");
        RiskFlag withRawValue = new RiskFlag("Notices", Severity.LOW, "Reason.",
                "Send notices to jane@corp.example without delay.");

        assertThat(validator.validate(with(null, null, List.of(), List.of(), List.of(withPlaceholder), "ok"), sent))
                .isEmpty();
        assertThat(validator.validate(with(null, null, List.of(), List.of(), List.of(withRawValue), "ok"), sent))
                .containsExactly("riskFlags[0].quote does not appear word for word in the contract text.");
        // Checked against the original instead, the placeholder quote would wrongly fail:
        assertThat(validator.validate(with(null, null, List.of(), List.of(), List.of(withPlaceholder), "ok"), original))
                .hasSize(1);
    }

    @Test
    void listSizesAreLimited() {
        List<String> parties = new ArrayList<>();
        List<Obligation> obligations = new ArrayList<>();
        List<RiskFlag> flags = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            if (i < 21) {
                parties.add("Party " + i);
            }
            obligations.add(new Obligation("Do " + i, null, null));
            if (i < 31) {
                flags.add(new RiskFlag("C", Severity.LOW, "R", "deliver goods."));
            }
        }

        assertThat(problems(with(null, null, parties, obligations, flags, "ok"))).contains(
                "parties has more than 20 entries.",
                "obligations has more than 50 entries.",
                "riskFlags has more than 30 entries.");
    }

    @Test
    void textLengthsAreLimited() {
        AnalysisResult result = new AnalysisResult(List.of("p".repeat(301)), null, null,
                "r".repeat(2001), null, List.of(), List.of(), "s".repeat(2001));

        assertThat(problems(result)).contains(
                "summary is longer than 2000 characters.",
                "parties[0] is longer than 300 characters.",
                "renewalTerms is longer than 2000 characters.");
    }

    @Test
    void aQuoteLongerThanTheLimitIsRejected() {
        String longText = "word ".repeat(200);
        List<RiskFlag> flags = List.of(new RiskFlag("C", Severity.LOW, "R", longText.strip()));

        assertThat(validator.validate(with(null, null, List.of(), List.of(), flags, "ok"), longText))
                .containsExactly("riskFlags[0].quote is longer than 500 characters.");
    }

    @Test
    void problemMessagesNeverContainContractText() {
        String secret = "SECRET-CONTRACT-PHRASE";
        List<RiskFlag> flags = List.of(new RiskFlag("C", Severity.LOW, "R", secret + " not in text"));

        List<String> found = validator.validate(with("bad-date", null, List.of(), List.of(), flags, "ok"), TEXT);

        assertThat(String.join(" ", found)).doesNotContain(secret).doesNotContain("bad-date");
    }

    @Test
    void nullEntriesInListsAreReportedNotCrashed() {
        List<Obligation> obligations = new ArrayList<>();
        obligations.add(null);
        List<RiskFlag> flags = new ArrayList<>();
        flags.add(null);

        assertThat(problems(with(null, null, List.of(), obligations, flags, "ok")))
                .containsExactly("obligations[0] is empty.", "riskFlags[0] is empty.");
    }
}
