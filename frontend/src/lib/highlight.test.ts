import { describe, expect, it } from "vitest";
import { buildSegments, hasPlaceholder, locateQuote } from "./highlight";

function found(text: string, quote: string): string | null {
  const range = locateQuote(text, quote);
  return range ? text.slice(range.start, range.end) : null;
}

describe("locateQuote", () => {
  const text = "The vendor shall deliver goods.\nThe vendor accepts unlimited liability for late delivery.\nEnd.";

  it("finds an exact quote", () => {
    expect(found(text, "The vendor accepts unlimited liability for late delivery.")).toBe(
      "The vendor accepts unlimited liability for late delivery.",
    );
  });

  it("returns the position so the surrounding text can be shown", () => {
    const range = locateQuote(text, "unlimited liability");
    expect(range).toEqual({ start: text.indexOf("unlimited liability"), end: text.indexOf("unlimited liability") + 19 });
  });

  it("ignores differences in line breaks and repeated spaces", () => {
    const messy = "The fee is\n   payable   within\t30\r\ndays of the invoice.";
    expect(found(messy, "payable within 30 days")).toBe("payable   within\t30\r\ndays");
  });

  it("ignores spaces around the quote", () => {
    expect(found(text, "   unlimited liability  ")).toBe("unlimited liability");
  });

  it("is case sensitive, like the backend's check", () => {
    expect(found(text, "UNLIMITED LIABILITY")).toBeNull();
  });

  it("uses the first occurrence", () => {
    const repeated = "pay now. then pay now.";
    expect(locateQuote(repeated, "pay now")?.start).toBe(0);
  });

  it("treats regular-expression characters in a quote as plain text", () => {
    const special = "Fee: $1,000 (net)? [see schedule] a+b=c.";
    expect(found(special, "$1,000 (net)? [see schedule] a+b=c.")).toBe("$1,000 (net)? [see schedule] a+b=c.");
  });

  it("returns null when the quote is not in the text, or is empty", () => {
    expect(locateQuote(text, "this is not in the contract")).toBeNull();
    expect(locateQuote(text, "")).toBeNull();
    expect(locateQuote(text, "   ")).toBeNull();
    expect(locateQuote("", "anything")).toBeNull();
  });

  describe("quotes that contain redaction placeholders", () => {
    it("matches an email in the original where the quote has [REDACTED_EMAIL]", () => {
      const original = "Send notices to jane.roe@agency.example without delay.";
      expect(found(original, "Send notices to [REDACTED_EMAIL] without delay.")).toBe(original);
    });

    it("matches a phone number that contains spaces", () => {
      const original = "Call +44 20 7946 0958 for support during office hours.";
      expect(found(original, "Call [REDACTED_PHONE] for support during office hours.")).toBe(original);
    });

    it("matches a placeholder that touches punctuation", () => {
      const original = "Contact (mail:jane@corp.example) before signing the agreement.";
      expect(found(original, "Contact (mail:[REDACTED_EMAIL]) before signing the agreement.")).toBe(original);
    });

    it("matches several placeholders in one quote", () => {
      const original = "Pay account 123456789012 or email bob@example.org to confirm payment.";
      expect(found(original, "Pay account [REDACTED_ACCOUNT] or email [REDACTED_EMAIL] to confirm payment.")).toBe(original);
    });

    it("never lets one placeholder stretch across a line break", () => {
      const original = "Send notices to ab\ncd. Without delay.";
      expect(found(original, "Send notices to [REDACTED_EMAIL]. Without delay.")).toBeNull();
    });

    it("never lets one placeholder stand for a long passage", () => {
      const original = "Send notices to " + "x".repeat(80) + ". Without delay.";
      expect(found(original, "Send notices to [REDACTED_EMAIL]. Without delay.")).toBeNull();
    });

    it("refuses a quote that is almost only a placeholder, because it would match anything", () => {
      expect(locateQuote("Mail jane@corp.example now.", "[REDACTED_EMAIL]")).toBeNull();
      expect(locateQuote("Mail jane@corp.example now.", "to [REDACTED_EMAIL]")).toBeNull();
    });
  });

  it("stays fast on a contract of the maximum size", () => {
    const big = "word ".repeat(40_000) + "the needle sentence is here. " + "word ".repeat(10);
    const started = performance.now();
    const range = locateQuote(big, "the   needle sentence\nis here.");
    expect(range).not.toBeNull();
    expect(performance.now() - started).toBeLessThan(500);
  });

  it("never treats contract text as markup: it only returns positions", () => {
    const hostile = "<img src=x onerror=alert(1)> accepts unlimited liability <script>x</script>";
    expect(found(hostile, "accepts unlimited liability")).toBe("accepts unlimited liability");
  });
});

describe("buildSegments", () => {
  const text = "0123456789";

  it("returns the whole text as one plain piece when nothing is highlighted", () => {
    expect(buildSegments(text, [])).toEqual([{ text, flags: [] }]);
    expect(buildSegments(text, [null, null])).toEqual([{ text, flags: [] }]);
  });

  it("splits around a highlighted range", () => {
    expect(buildSegments(text, [{ start: 3, end: 6 }])).toEqual([
      { text: "012", flags: [] },
      { text: "345", flags: [0] },
      { text: "6789", flags: [] },
    ]);
  });

  it("handles a range at the very start and the very end", () => {
    expect(buildSegments(text, [{ start: 0, end: 2 }, { start: 8, end: 10 }])).toEqual([
      { text: "01", flags: [0] },
      { text: "234567", flags: [] },
      { text: "89", flags: [1] },
    ]);
  });

  it("shows overlapping quotes as pieces that belong to both", () => {
    expect(buildSegments(text, [{ start: 2, end: 6 }, { start: 4, end: 8 }])).toEqual([
      { text: "01", flags: [] },
      { text: "23", flags: [0] },
      { text: "45", flags: [0, 1] },
      { text: "67", flags: [1] },
      { text: "89", flags: [] },
    ]);
  });

  it("handles a quote inside another quote and identical quotes", () => {
    expect(buildSegments(text, [{ start: 1, end: 9 }, { start: 3, end: 5 }, { start: 3, end: 5 }])).toEqual([
      { text: "0", flags: [] },
      { text: "12", flags: [0] },
      { text: "34", flags: [0, 1, 2] },
      { text: "5678", flags: [0] },
      { text: "9", flags: [] },
    ]);
  });

  it("ignores ranges that were not found (null) but keeps flag numbering", () => {
    expect(buildSegments(text, [null, { start: 2, end: 4 }])).toEqual([
      { text: "01", flags: [] },
      { text: "23", flags: [1] },
      { text: "456789", flags: [] },
    ]);
  });

  it("always reproduces the original text exactly when the pieces are joined", () => {
    const ranges = [{ start: 1, end: 4 }, { start: 3, end: 7 }, { start: 7, end: 10 }, null];
    expect(buildSegments(text, ranges).map((s) => s.text).join("")).toBe(text);
  });

  it("clamps ranges that go past the end of the text", () => {
    expect(buildSegments("abc", [{ start: 1, end: 99 }])).toEqual([
      { text: "a", flags: [] },
      { text: "bc", flags: [0] },
    ]);
  });

  it("keeps markup characters as plain text in the pieces", () => {
    const hostile = "a <b>bold</b> & more";
    const range = locateQuote(hostile, "<b>bold</b>");
    expect(buildSegments(hostile, [range]).map((s) => s.text)).toEqual(["a ", "<b>bold</b>", " & more"]);
  });
});

describe("hasPlaceholder", () => {
  it("tells whether a quote contains masked personal data, which makes its location approximate", () => {
    expect(hasPlaceholder("Send notices to [REDACTED_EMAIL] today")).toBe(true);
    expect(hasPlaceholder("Call [REDACTED_PHONE]")).toBe(true);
    expect(hasPlaceholder("Plain quote with [brackets] only")).toBe(false);
    expect(hasPlaceholder("")).toBe(false);
  });
});
