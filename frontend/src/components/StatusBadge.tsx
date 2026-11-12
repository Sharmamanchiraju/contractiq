import type { ContractStatus } from "../api/types";
import { STATUS_INFO } from "../lib/format";

/** Shows a contract status as text plus a symbol plus a color, so no one has to rely on color alone. */
export function StatusBadge({ status }: { status: ContractStatus }) {
  const info = STATUS_INFO[status];
  return (
    <span className={`badge badge-${info.tone}`}>
      <span aria-hidden="true">{info.symbol}</span> {info.label}
    </span>
  );
}
