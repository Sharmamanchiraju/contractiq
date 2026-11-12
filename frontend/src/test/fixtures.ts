import type { AnalysisResponse, ContractDetail, Role } from "../api/types";
import { fakeToken, jsonResponse, type Route } from "./helpers";

export const LIABILITY_QUOTE = "The vendor accepts unlimited liability for late delivery.";
export const PAYMENT_QUOTE = "Payment is due within 30 days.";
export const CONTRACT_TEXT = [
  "This agreement is between Acme Ltd and City Council.",
  LIABILITY_QUOTE,
  PAYMENT_QUOTE,
].join("\n");

export function contractDetail(overrides: Partial<ContractDetail> = {}): ContractDetail {
  return {
    id: 7,
    title: "Office supplies",
    vendorName: "Acme Ltd",
    status: "ANALYZED",
    analysisErrorCode: null,
    createdBy: 1,
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:05:00Z",
    contractText: CONTRACT_TEXT,
    ...overrides,
  };
}

export function analysisResponse(overrides: Partial<AnalysisResponse["result"]> = {}): AnalysisResponse {
  return {
    contractId: 7,
    version: 1,
    provider: "mock",
    createdAt: "2026-01-01T10:05:00Z",
    result: {
      parties: ["Acme Ltd", "City Council"],
      effectiveDate: "2026-01-01",
      expiryDate: "2027-01-01",
      renewalTerms: "Renews yearly.",
      paymentTerms: PAYMENT_QUOTE,
      obligations: [{ description: "Acme Ltd shall deliver goods.", party: "Acme Ltd", dueDate: "2026-03-01" }],
      riskFlags: [
        { clause: "Unlimited liability", severity: "HIGH", reason: "Liability is not capped.", quote: LIABILITY_QUOTE },
        { clause: "Payment terms", severity: "LOW", reason: "Net 30.", quote: PAYMENT_QUOTE },
      ],
      summary: "A supply contract between Acme Ltd and City Council.",
      ...overrides,
    },
  };
}

/** The fake backend's login route: any username and password work, and the token carries `role`. */
export function loginRoute(role: Role): Route {
  return (request) =>
    request.method === "POST" && request.path === "/api/v1/auth/login"
      ? jsonResponse(200, { token: fakeToken({ sub: "1", org: 1, role }), tokenType: "Bearer", expiresInSeconds: 1800 })
      : undefined;
}

/** A route for one method and exact path (including the query string). */
export function on(method: string, path: string, respond: () => Response | Promise<Response>): Route {
  return (request) => (request.method === method && request.path === path ? respond() : undefined);
}

/** A route for one method and a path prefix (for lists with query strings). */
export function onPrefix(method: string, prefix: string, respond: () => Response | Promise<Response>): Route {
  return (request) => (request.method === method && request.path.startsWith(prefix) ? respond() : undefined);
}

export function listPage(items: object[], total = items.length) {
  return { items, page: 0, size: 20, totalElements: total, totalPages: Math.max(1, Math.ceil(total / 20)) };
}

export function summaryOf(contract: ContractDetail) {
  const { contractText: _text, ...summary } = contract;
  return summary;
}
