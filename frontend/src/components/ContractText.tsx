import { useEffect, useRef } from "react";
import type { RiskFlag } from "../api/types";
import type { Segment } from "../lib/highlight";

interface Props {
  segments: Segment[];
  flags: RiskFlag[];
  selectedFlag: number | null;
}

const SEVERITY_ORDER = { LOW: 0, MEDIUM: 1, HIGH: 2 } as const;

/**
 * The original contract text, with each located risk-flag quote highlighted.
 *
 * Everything is rendered as React text nodes, so a contract that contains HTML or script is shown
 * as literal characters and never executed. A highlight is marked with an underline, a thick border
 * for the selected flag, and the flag's number (so it does not depend on color).
 */
export function ContractText({ segments, flags, selectedFlag }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);

  // Scroll the selected quote into view when a flag is chosen.
  useEffect(() => {
    if (selectedFlag === null) return;
    const target = containerRef.current?.querySelector(`[data-first-of~="${selectedFlag}"]`);
    if (target && "scrollIntoView" in target) {
      (target as HTMLElement).scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [selectedFlag]);

  const seen = new Set<number>();
  return (
    <div
      ref={containerRef}
      className="contract-text"
      tabIndex={0}
      role="region"
      aria-label="Original contract text, with risk flag quotes highlighted"
    >
      {segments.map((segment, position) => {
        if (segment.flags.length === 0) return <span key={position}>{segment.text}</span>;
        const firstOf = segment.flags.filter((flag) => !seen.has(flag));
        firstOf.forEach((flag) => seen.add(flag));
        const strongest = segment.flags.reduce(
          (best, flag) => (SEVERITY_ORDER[flags[flag].severity] > SEVERITY_ORDER[flags[best].severity] ? flag : best),
          segment.flags[0],
        );
        const selected = selectedFlag !== null && segment.flags.includes(selectedFlag);
        return (
          <mark
            key={position}
            className={`quote severity-${flags[strongest].severity.toLowerCase()}${selected ? " quote-selected" : ""}`}
            data-flags={segment.flags.join(" ")}
            data-first-of={firstOf.join(" ")}
          >
            {firstOf.map((flag) => (
              <sup key={flag} className="flag-ref" aria-label={`Risk flag ${flag + 1}`}>
                {flag + 1}
              </sup>
            ))}
            {segment.text}
          </mark>
        );
      })}
    </div>
  );
}
