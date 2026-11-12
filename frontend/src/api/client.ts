/**
 * The one place that talks HTTP to the ContractIQ backend.
 *
 * The login token is kept in a module variable, in memory only: it is never written to
 * localStorage, sessionStorage, a cookie or the URL, so reloading the page signs the user out.
 * Every request has a timeout. A 401 on an authenticated request clears the token and tells the
 * app (through the registered handler) to go back to the login page.
 */

const DEFAULT_BASE_URL = "http://localhost:8080";
const REQUEST_TIMEOUT_MS = 30_000;

export const BASE_URL: string = (import.meta.env.VITE_API_BASE_URL as string | undefined) || DEFAULT_BASE_URL;

/** A failed API call. The message is written for people and is safe to show. */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(message: string, status: number, fieldErrors: { field: string; message: string }[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/** The session is no longer valid (HTTP 401). The app has already been told to show the login page. */
export class UnauthorizedError extends ApiError {
  constructor() {
    super("Your session has expired. Please sign in again.", 401);
    this.name = "UnauthorizedError";
  }
}

let token: string | null = null;
let unauthorizedHandler: (() => void) | null = null;

export function setToken(value: string | null): void {
  token = value;
}

export function hasToken(): boolean {
  return token !== null;
}

/** Registers what to do when the backend answers 401. Returns a function that removes it. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export interface RequestOptions {
  body?: unknown;
  /** Aborting this signal cancels the request (used when a component goes away). */
  signal?: AbortSignal;
  /** False for the login call: a 401 there means "wrong password", not "session expired". */
  authenticated?: boolean;
}

export async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const authenticated = options.authenticated !== false;
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  const onCallerAbort = () => controller.abort();
  if (options.signal) {
    if (options.signal.aborted) controller.abort();
    else options.signal.addEventListener("abort", onCallerAbort, { once: true });
  }

  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (authenticated && token) headers["Authorization"] = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
      credentials: "omit",
      cache: "no-store",
    });
  } catch (error) {
    if (timedOut) throw new ApiError("The server took too long to answer. Please try again.", 0);
    if (options.signal?.aborted) throw error; // the caller cancelled: let it handle that quietly
    throw new ApiError("Could not reach the server. Check that the backend is running and that it allows this page's address (CORS).", 0);
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", onCallerAbort);
  }

  if (response.status === 401 && authenticated) {
    token = null;
    unauthorizedHandler?.();
    throw new UnauthorizedError();
  }
  if (!response.ok) throw await toApiError(response);

  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError("The server sent an answer this page could not read.", response.status);
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let fieldErrors: { field: string; message: string }[] = [];
  try {
    const problem = (await response.json()) as { errors?: { field?: unknown; message?: unknown }[] };
    if (Array.isArray(problem.errors)) {
      fieldErrors = problem.errors
        .filter((e) => typeof e.field === "string" && typeof e.message === "string")
        .map((e) => ({ field: e.field as string, message: e.message as string }));
    }
  } catch {
    // no readable body: fall back to the status text below
  }
  return new ApiError(messageForStatus(response.status, fieldErrors), response.status, fieldErrors);
}

function messageForStatus(status: number, fieldErrors: { field: string; message: string }[]): string {
  switch (status) {
    case 400:
      return fieldErrors.length > 0
        ? fieldErrors.map((e) => `${e.field}: ${e.message}`).join("; ")
        : "The server did not accept that request.";
    case 401:
      return "Invalid username or password.";
    case 403:
      return "Your account is not allowed to do that.";
    case 404:
      return "That could not be found. It may not exist, or it may belong to another organization.";
    case 409:
      return "That is not possible in the contract's current state (for example it is already being analyzed, or it has already been decided).";
    case 411:
    case 413:
      return "That request is too large.";
    default:
      return status >= 500
        ? "The server had a problem. Please try again in a moment."
        : `The server answered with an unexpected error (${status}).`;
  }
}
