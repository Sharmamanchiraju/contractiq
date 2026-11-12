/**
 * Finds a risk flag's verbatim quote inside the original contract text, so a reviewer can see it
 * in context, and splits the text into pieces that can be rendered with highlights.
 *
 * Everything here works on plain strings. The pieces are rendered as React text, never as HTML.
 *
 * Why matching is not a plain indexOf: the backend checks quotes against the text it sent to the
 * AI, which is the contract with personal data masked and whitespace collapsed. So a quote may
 * (a) have different line breaks or spacing than the original, and (b) contain a placeholder such
 * as [REDACTED_EMAIL] where the original has the real value. Both are handled; if the quote still
 * cannot be found, the caller says so instead of guessing.
 */

export interface TextRange {
  start: number;
  end: number;
}

export interface Segment {
  text: string;
  /** Indexes (into the list of ranges given to buildSegments) of the quotes covering this piece. */
  flags: number[];
}

const PLACEHOLDER = /\[REDACTED_(?:EMAIL|PHONE|ID|ACCOUNT)\]/g;
/** A placeholder stands for a short value (an email, a phone number, an id) on one line. */
const PLACEHOLDER_PATTERN = "[^\\n]{1,60}?";
/** A quote made almost only of placeholders would match nearly anything, so it is not matched. */
const MIN_LITERAL_CHARS_WITH_PLACEHOLDER = 8;

function escapeRegExp(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** True if the quote contains a masked-data placeholder, so where it is highlighted is approximate. */
export function hasPlaceholder(quote: string): boolean {
  return new RegExp(PLACEHOLDER.source).test(quote);
}

/** Returns where the quote first appears in the text, or null if it cannot be located. */
export function locateQuote(text: string, quote: string): TextRange | null {
  const trimmed = quote.trim();
  if (!trimmed || !text) return null;

  if (hasPlaceholder(trimmed)) {
    const literalChars = trimmed.replace(PLACEHOLDER, "").replace(/\s+/g, "").length;
    if (literalChars < MIN_LITERAL_CHARS_WITH_PLACEHOLDER) return null;
  }

  const pattern = trimmed
    .split(/\s+/)
    .map((word) => {
      const pieces = word.split(PLACEHOLDER);
      const placeholders = word.match(PLACEHOLDER) ?? [];
      return pieces
        .map((piece, index) => escapeRegExp(piece) + (index < placeholders.length ? PLACEHOLDER_PATTERN : ""))
        .join("");
    })
    .join("\\s+");

  const match = new RegExp(pattern).exec(text);
  return match ? { start: match.index, end: match.index + match[0].length } : null;
}

/**
 * Splits the text at every range boundary. Each piece records which ranges cover it, so
 * overlapping quotes are shown correctly. Ranges that are null (not found) are ignored.
 */
export function buildSegments(text: string, ranges: (TextRange | null)[]): Segment[] {
  const points = new Set<number>([0, text.length]);
  for (const range of ranges) {
    if (range) {
      points.add(Math.max(0, Math.min(range.start, text.length)));
      points.add(Math.max(0, Math.min(range.end, text.length)));
    }
  }
  const sorted = [...points].sort((a, b) => a - b);
  const segments: Segment[] = [];
  for (let i = 0; i < sorted.length - 1; i++) {
    const start = sorted[i];
    const end = sorted[i + 1];
    if (start === end) continue;
    const flags: number[] = [];
    ranges.forEach((range, index) => {
      if (range && range.start <= start && range.end >= end) flags.push(index);
    });
    segments.push({ text: text.slice(start, end), flags });
  }
  return segments;
}
