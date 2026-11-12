package com.contractiq.contract.ai;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Masks personal-data-looking patterns in contract text before it leaves the system for an AI
 * provider. Controlled by {@code app.ai.redaction.enabled}.
 *
 * <p><b>Limits, honestly stated.</b> This is a set of regular expressions, not a data-loss-prevention
 * product. It will miss things and it will sometimes mask harmless text.
 * <ul>
 *   <li>It does NOT mask names, postal addresses, dates of birth, company numbers, free-text
 *       identifiers, or anything that does not match one of the formats below. Party names in the
 *       contract are sent as written.</li>
 *   <li>Emails: ordinary {@code local@domain.tld} forms only.</li>
 *   <li>Phone numbers: numbers written with spaces, dots, hyphens or brackets between digit groups
 *       (such as {@code +44 20 7946 0958}, {@code (555) 123-4567}, {@code +91 98765 43210}) and compact
 *       {@code +} numbers. Numbers written as plain digit runs are handled by the account rule.</li>
 *   <li>National IDs: US SSN ({@code 123-45-6789}), 12-digit Aadhaar-style groups
 *       ({@code 1234 5678 9012}) and UK National Insurance numbers. Other countries' formats are not covered.</li>
 *   <li>Bank accounts: IBANs written in capital letters, 16-digit card-style groups, and ANY plain run of
 *       9 to 18 digits. The last rule also masks large amounts written without separators
 *       (for example {@code 1000000000}) and long reference numbers: a false positive we accept.</li>
 *   <li>ISO dates ({@code 2026-11-12}), amounts with separators ({@code 1,000,000}) and clause numbers
 *       ({@code 12.3.4}) are left alone; this is covered by tests, but other date formats
 *       may be hit in unusual layouts.</li>
 *   <li>The masking is one-way. The analysis is done on the masked text, so a quote the model returns
 *       contains the placeholder (for example {@code [REDACTED_EMAIL]}), not the original value.</li>
 * </ul>
 * Redaction reduces what is shared; it does not make sharing a contract with a third party safe by itself.
 */
@Component
public class Redactor {

    static final String EMAIL = "[REDACTED_EMAIL]";
    static final String PHONE = "[REDACTED_PHONE]";
    static final String ID = "[REDACTED_ID]";
    static final String ACCOUNT = "[REDACTED_ACCOUNT]";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN_PATTERN =
            Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{4}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern AADHAAR_PATTERN = Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}\\b");
    private static final Pattern NINO_PATTERN = Pattern.compile(
            "\\b[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z]\\s?\\d{2}\\s?\\d{2}\\s?\\d{2}\\s?[A-D]\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![\\w+])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{2,4}\\)[ .-]?\\d{3,4}[ .-]\\d{3,4}"
                    + "|\\d{2,4}[ .-]\\d{3,4}[ .-]\\d{3,4}|\\d{5}[ .-]\\d{5})(?!\\w)"
                    + "|(?<![\\w+])\\+\\d{8,15}(?!\\w)");
    private static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("(?<![\\d.,])\\d{9,18}(?!\\d)");

    private final boolean enabled;

    @Autowired
    public Redactor(AiProperties properties) {
        this(properties.redaction().enabled());
    }

    public Redactor(boolean enabled) {
        this.enabled = enabled;
    }

    /** Returns the text with matching patterns replaced by placeholders, or the text unchanged if disabled. */
    public String redact(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        result = IBAN_PATTERN.matcher(result).replaceAll(ACCOUNT);
        result = CARD_PATTERN.matcher(result).replaceAll(ACCOUNT);
        result = SSN_PATTERN.matcher(result).replaceAll(ID);
        result = AADHAAR_PATTERN.matcher(result).replaceAll(ID);
        result = NINO_PATTERN.matcher(result).replaceAll(ID);
        result = PHONE_PATTERN.matcher(result).replaceAll(PHONE);
        result = LONG_DIGITS_PATTERN.matcher(result).replaceAll(ACCOUNT);
        return result;
    }
}
