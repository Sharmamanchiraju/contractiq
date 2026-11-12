/** One function per backend endpoint the UI uses. */

import { request } from "./client";
import type {
  AnalysisResponse,
  AnalyzeStarted,
  ContractDetail,
  ContractStatus,
  ContractSummary,
  CreateContractBody,
  LoginResponse,
  PageResponse,
  ReviewRequest,
  ReviewResponse,
} from "./types";

export function login(username: string, password: string): Promise<LoginResponse> {
  // authenticated:false, so a 401 here is shown as "invalid username or password", not "session expired".
  return request<LoginResponse>("POST", "/api/v1/auth/login", {
    body: { username, password },
    authenticated: false,
  });
}

export function listContracts(
  params: { status?: ContractStatus | ""; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResponse<ContractSummary>> {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  query.set("page", String(params.page ?? 0));
  query.set("size", String(params.size ?? 20));
  return request("GET", `/api/v1/contracts?${query.toString()}`, { signal });
}

export function getContract(id: number, signal?: AbortSignal): Promise<ContractDetail> {
  return request("GET", `/api/v1/contracts/${id}`, { signal });
}

export function createContract(body: CreateContractBody): Promise<ContractSummary> {
  return request("POST", "/api/v1/contracts", { body });
}

export function analyzeContract(id: number): Promise<AnalyzeStarted> {
  return request("POST", `/api/v1/contracts/${id}/analyze`);
}

export function getAnalysis(id: number, signal?: AbortSignal): Promise<AnalysisResponse> {
  return request("GET", `/api/v1/contracts/${id}/analysis`, { signal });
}

export function reviewContract(id: number, body: ReviewRequest): Promise<ReviewResponse> {
  return request("POST", `/api/v1/contracts/${id}/review`, { body });
}
