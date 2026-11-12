/** Shapes of the ContractIQ REST API (see the backend DTOs). Only fields this UI uses are listed. */

export type Role = "UPLOADER" | "REVIEWER" | "ADMIN";
export const ROLES: readonly Role[] = ["UPLOADER", "REVIEWER", "ADMIN"];

export type ContractStatus =
  | "UPLOADED"
  | "ANALYZING"
  | "ANALYZED"
  | "NEEDS_MANUAL_REVIEW"
  | "APPROVED"
  | "REJECTED";
export const STATUSES: readonly ContractStatus[] = [
  "UPLOADED",
  "ANALYZING",
  "ANALYZED",
  "NEEDS_MANUAL_REVIEW",
  "APPROVED",
  "REJECTED",
];

export type Severity = "LOW" | "MEDIUM" | "HIGH";
export type ReviewDecision = "APPROVED" | "REJECTED";

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface ContractSummary {
  id: number;
  title: string;
  vendorName: string;
  status: ContractStatus;
  analysisErrorCode: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface ContractDetail extends ContractSummary {
  contractText: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateContractBody {
  title: string;
  vendorName: string;
  contractText: string;
}

export interface Obligation {
  description: string;
  party: string | null;
  dueDate: string | null;
}

export interface RiskFlag {
  clause: string;
  severity: Severity;
  reason: string;
  quote: string;
}

export interface AnalysisResult {
  parties: string[];
  effectiveDate: string | null;
  expiryDate: string | null;
  renewalTerms: string | null;
  paymentTerms: string | null;
  obligations: Obligation[];
  riskFlags: RiskFlag[];
  summary: string;
}

export interface AnalysisResponse {
  contractId: number;
  version: number;
  provider: string;
  createdAt: string;
  result: AnalysisResult;
}

export interface AnalyzeStarted {
  contractId: number;
  status: ContractStatus;
}

export interface ReviewRequest {
  decision: ReviewDecision;
  comment: string;
}

export interface ReviewResponse {
  id: number;
  contractId: number;
  reviewerId: number;
  decision: ReviewDecision;
  comment: string | null;
  createdAt: string;
  contractStatus: ContractStatus;
}
