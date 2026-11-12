import type { Severity } from "../api/types";
import { SEVERITY_INFO } from "../lib/format";

/** Shows a risk severity as text plus a symbol plus a color. */
export function SeverityBadge({ severity }: { severity: Severity }) {
  const info = SEVERITY_INFO[severity];
  return (
    <span className={`badge severity-${info.tone}`}>
      <span aria-hidden="true">{info.symbol}</span> {info.label} risk
    </span>
  );
}
