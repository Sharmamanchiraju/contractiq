import { vi } from "vitest";
import type { ApiError } from "../api/client";

/** A JSON response as the backend would send it. */
export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Builds a fake JWT with the given payload (not signed: the UI never verifies tokens). */
export function fakeToken(payload: Record<string, unknown>): string {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${encode({ alg: "HS512" })}.${encode(payload)}.fake-signature`;
}

export type Route = (request: { method: string; path: string; body: unknown; headers: Headers }) => Response | Promise<Response> | undefined;

/**
 * Replaces fetch with a fake backend. Each route may return a Response, or undefined to pass.
 * Returns the mock so tests can look at the calls.
 */
export function mockBackend(...routes: Route[]) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const request = {
      method: (init?.method ?? "GET").toUpperCase(),
      path: url.pathname + url.search,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
      headers: new Headers(init?.headers),
    };
    for (const route of routes) {
      const response = await route(request);
      if (response) return response;
    }
    return jsonResponse(404, { title: "no mock route", path: request.path });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** Awaits a promise that is expected to fail and returns the error (typed as ApiError for convenience). */
export async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    return error as ApiError;
  }
  throw new Error("Expected the promise to be rejected, but it resolved.");
}
