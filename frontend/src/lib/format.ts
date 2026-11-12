/** Plain-language wording for statuses, severities and analysis error codes. */

import type { ContractStatus, Severity } from "../api/types";

export interface BadgeInfo {
  label: string;
  /** A symbol shown with the label, so meaning never depends on color alone. */
  symbol: string;
  /** CSS class suffix; the color itself lives in styles.css. */
  tone: string;
}

export const STATUS_INFO: Record<ContractStatus, BadgeInfo> = {
  UPLOADED: { label: "Uploaded", symbol: "○", tone: "neutral" },
  ANALYZING: { label: "Analyzing", symbol: "◔", tone: "progress" },
  ANALYZED: { label: "Analyzed", symbol: "◑", tone: "info" },
  NEEDS_MANUAL_REVIEW: { label: "Needs manual review", symbol: "!", tone: "warn" },
  APPROVED: { label: "Approved", symbol: "✓", tone: "good" },
  REJECTED: { label: "Rejected", symbol: "✕", tone: "bad" },
};

export const SEVERITY_INFO: Record<Severity, BadgeInfo> = {
  LOW: { label: "Low", symbol: "▽", tone: "low" },
  MEDIUM: { label: "Medium", symbol: "◇", tone: "medium" },
  HIGH: { label: "High", symbol: "▲", tone: "high" },
};

const ERROR_CODE_WORDS: Record<string, string> = {
  ANALYSIS_INVALID_OUTPUT:
    "The AI's answer could not be used, even after a second try. A person needs to read this contract.",
  ANALYSIS_PROVIDER_ERROR:
    "The AI service refused the request. This usually means a setup problem, not a problem with the contract.",
  ANALYSIS_TIMEOUT: "The AI service took too long to answer.",
  ANALYSIS_TOO_LARGE: "The contract is too large for the AI to analyze.",
  ANALYSIS_UNAVAILABLE: "The AI service is busy or not reachable right now. Try the analysis again in a little while.",
  ANALYSIS_INTERNAL_ERROR: "Something went wrong inside ContractIQ while analyzing. A person needs to read this contract.",
};

/** Explains an analysis error code in plain words. Unknown codes get a generic sentence. */
export function explainAnalysisError(code: string | null): string {
  if (!code) return "The analysis did not finish, and no reason was recorded.";
  return ERROR_CODE_WORDS[code] ?? "The analysis did not finish.";
}

/** Formats an ISO timestamp for display in the viewer's locale. Falls back to the raw text. */
export function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}
