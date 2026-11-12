import type { AnalysisResponse } from "../api/types";
import { formatTimestamp } from "../lib/format";
import { hasPlaceholder, type TextRange } from "../lib/highlight";
import { SeverityBadge } from "./SeverityBadge";

interface Props {
  analysis: AnalysisResponse;
  /** Where each risk flag's quote was found in the original text (null if it was not found). */
  located: (TextRange | null)[];
  selectedFlag: number | null;
  onSelectFlag: (index: number) => void;
}

function Field({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="field">
      <dt>{label}</dt>
      <dd>{value ?? <span className="muted">Not stated in the contract</span>}</dd>
    </div>
  );
}

/** The AI analysis as plain text: summary, parties, dates, terms, obligations and risk flags. */
export function AnalysisPanel({ analysis, located, selectedFlag, onSelectFlag }: Props) {
  const { result } = analysis;
  return (
    <div className="analysis">
      <p className="muted">
        Analysis version {analysis.version}, {formatTimestamp(analysis.createdAt)}
      </p>

      <section aria-labelledby="summary-heading">
        <h3 id="summary-heading">Summary</h3>
        <p>{result.summary}</p>
      </section>

      <section aria-labelledby="terms-heading">
        <h3 id="terms-heading">Key facts</h3>
        <dl>
          <Field label="Parties" value={result.parties.length > 0 ? result.parties.join(", ") : null} />
          <Field label="Effective date" value={result.effectiveDate} />
          <Field label="Expiry date" value={result.expiryDate} />
          <Field label="Renewal terms" value={result.renewalTerms} />
          <Field label="Payment terms" value={result.paymentTerms} />
        </dl>
      </section>

      <section aria-labelledby="obligations-heading">
        <h3 id="obligations-heading">Obligations ({result.obligations.length})</h3>
        {result.obligations.length === 0 ? (
          <p className="muted">No obligations were found.</p>
        ) : (
          <ul className="plain-list">
            {result.obligations.map((obligation, index) => (
              <li key={index}>
                <p>{obligation.description}</p>
                <p className="muted">
                  Who: {obligation.party ?? "not stated"} · Due: {obligation.dueDate ?? "not stated"}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="flags-heading">
        <h3 id="flags-heading">Risk flags ({result.riskFlags.length})</h3>
        {result.riskFlags.length === 0 ? (
          <p className="muted">No risk flags were raised. A human should still read the contract.</p>
        ) : (
          <ol className="flags">
            {result.riskFlags.map((flag, index) => {
              const found = located[index] !== null;
              const approximate = hasPlaceholder(flag.quote);
              return (
                <li key={index} className={`flag${selectedFlag === index ? " flag-selected" : ""}`}>
                  <div className="flag-head">
                    <span className="flag-number" aria-hidden="true">{index + 1}</span>
                    <strong>{flag.clause}</strong>
                    <SeverityBadge severity={flag.severity} />
                  </div>
                  <p>{flag.reason}</p>
                  <blockquote>{flag.quote}</blockquote>
                  {found ? (
                    <button
                      type="button"
                      className="button button-small"
                      aria-pressed={selectedFlag === index}
                      onClick={() => onSelectFlag(index)}
                    >
                      Show in contract
                    </button>
                  ) : (
                    <p className="muted">
                      This quote could not be located in the original text
                      {approximate ? " (it contains redacted personal data)" : ""}. Search the contract for it
                      yourself.
                    </p>
                  )}
                  {found && approximate ? (
                    <p className="muted">
                      The quote contains redacted personal data, so its place in the text is approximate.
                    </p>
                  ) : null}
                </li>
              );
            })}
          </ol>
        )}
      </section>
    </div>
  );
}
