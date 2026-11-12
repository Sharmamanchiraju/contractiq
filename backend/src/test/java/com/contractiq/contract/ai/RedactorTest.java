package com.contractiq.contract.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link Redactor}: what it masks, what it deliberately leaves alone, and its
 * on/off switch. The "leaves alone" cases matter as much as the masking: contracts are full of
 * dates, amounts and clause numbers that must not be destroyed.
 */
class RedactorTest {

    private final Redactor redactor = new Redactor(true);

    @Test
    void masksEmailAddresses() {
        assertThat(redactor.redact("Notices go to jane.doe+legal@corp-mail.example.co.uk today."))
                .isEqualTo("Notices go to [REDACTED_EMAIL] today.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+1 555 123 4567", "(555) 123-4567", "555-123-4567", "+44 20 7946 0958",
            "020 7946 0958", "+91 98765 43210", "+442079460958", "555.123.4567"})
    void masksPhoneNumbers(String phone) {
        assertThat(redactor.redact("Call " + phone + " for support.")).isEqualTo("Call [REDACTED_PHONE] for support.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123-45-6789", "1234 5678 9012", "1234-5678-9012", "JG 10 33 62 B", "AB123456C"})
    void masksNationalIdLikeNumbers(String id) {
        assertThat(redactor.redact("Tax id " + id + " applies.")).isEqualTo("Tax id [REDACTED_ID] applies.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GB82 WEST 1234 5698 7654 32", "DE89370400440532013000", "4111 1111 1111 1111",
            "123456789", "12345678901234"})
    void masksBankAccountLikeNumbers(String account) {
        assertThat(redactor.redact("Pay to " + account + " promptly."))
                .isEqualTo("Pay to [REDACTED_ACCOUNT] promptly.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The term runs from 2026-11-12 to 2027-11-11.",
            "The fee is 1,000,000 per year, payable within 30 days.",
            "See clauses 12.3.4 and 7.1 for details.",
            "Deliver 500 units by 1 March 2027.",
            "Section 4(b) applies to orders over $25,000.00.",
            "Contract no. 2026/118 and ref 12345."})
    void leavesDatesAmountsAndClauseNumbersAlone(String text) {
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void masksSeveralKindsInOneText() {
        String redacted = redactor.redact(
                "Contact bob@example.org or +1 555 123 4567. SSN 123-45-6789. IBAN DE89370400440532013000.");

        assertThat(redacted).isEqualTo(
                "Contact [REDACTED_EMAIL] or [REDACTED_PHONE]. SSN [REDACTED_ID]. IBAN [REDACTED_ACCOUNT].");
    }

    @Test
    void isIdempotent() {
        String once = redactor.redact("Mail bob@example.org, call 555-123-4567.");

        assertThat(redactor.redact(once)).isEqualTo(once);
    }

    @Test
    void doesNotMaskPersonNamesOrAddressesAKnownLimit() {
        String text = "Jane Doe of 12 Baker Street, London, signs for Acme Ltd.";

        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void whenDisabledTheTextIsReturnedUnchanged() {
        String text = "Mail bob@example.org or call 555-123-4567.";

        assertThat(new Redactor(false).redact(text)).isEqualTo(text);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
    }

    @Test
    void keepsLineBreaksAndSurroundingText() {
        assertThat(redactor.redact("Line one a@b.example\nLine two\n")).isEqualTo("Line one [REDACTED_EMAIL]\nLine two\n");
    }
}
