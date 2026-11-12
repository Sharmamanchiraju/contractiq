import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, UnauthorizedError, request, setToken, setUnauthorizedHandler } from "./client";
import { login } from "./contracts";
import { failure, fakeToken, jsonResponse, mockBackend } from "../test/helpers";

beforeEach(() => {
  setToken(null);
  setUnauthorizedHandler(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("request", () => {
  it("sends the token as a bearer header once one is set, and never before", async () => {
    const fetchMock = mockBackend(() => jsonResponse(200, { ok: true }));

    await request("GET", "/api/v1/contracts");
    setToken("FAKE.TOKEN.1");
    await request("GET", "/api/v1/contracts");

    const [first, second] = fetchMock.mock.calls.map((call) => new Headers(call[1]?.headers));
    expect(first.get("Authorization")).toBeNull();
    expect(second.get("Authorization")).toBe("Bearer FAKE.TOKEN.1");
  });

  it("sends JSON only when there is a body, and never sends cookies", async () => {
    const fetchMock = mockBackend(() => jsonResponse(200, {}));

    await request("GET", "/x");
    await request("POST", "/x", { body: { a: 1 } });

    const [get, post] = fetchMock.mock.calls;
    expect(new Headers(get[1]?.headers).get("Content-Type")).toBeNull();
    expect(new Headers(post[1]?.headers).get("Content-Type")).toBe("application/json");
    expect(post[1]?.body).toBe('{"a":1}');
    expect(get[1]?.credentials).toBe("omit");
  });

  it("on 401 drops the token, tells the app, and throws UnauthorizedError", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    setToken("FAKE.TOKEN.1");
    const fetchMock = mockBackend(() => jsonResponse(401, { title: "Unauthorized" }));

    await expect(request("GET", "/api/v1/contracts")).rejects.toBeInstanceOf(UnauthorizedError);

    expect(handler).toHaveBeenCalledTimes(1);
    await expect(request("GET", "/api/v1/contracts")).rejects.toBeInstanceOf(UnauthorizedError);
    // The second request went out without the dropped token.
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get("Authorization")).toBeNull();
  });

  it("a 401 on login means wrong credentials, not an expired session", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    mockBackend(() => jsonResponse(401, { title: "Unauthorized" }));

    const error = await failure(login("someone", "wrong"));

    expect(error).toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(UnauthorizedError);
    expect(error.message).toBe("Invalid username or password.");
    expect(handler).not.toHaveBeenCalled();
  });

  it("a 429 on login becomes a 'too many attempts' message and is not an expired session", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    mockBackend(() => jsonResponse(429, { title: "Too Many Requests" }));

    const error = await failure(login("someone", "pw"));

    expect(error).toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(UnauthorizedError);
    expect(error.status).toBe(429);
    expect(error.message).toBe("Too many sign-in attempts, try again later.");
    expect(handler).not.toHaveBeenCalled();
  });

  it("turns validation errors into a readable message with the field names", async () => {
    mockBackend(() => jsonResponse(400, { errors: [{ field: "title", message: "must not be blank" }] }));

    const error = await failure(request("POST", "/x", { body: {} }));

    expect(error.message).toBe("title: must not be blank");
    expect(error.fieldErrors).toEqual([{ field: "title", message: "must not be blank" }]);
  });

  it.each([
    [403, /not allowed/],
    [404, /could not be found/],
    [409, /not possible in the contract's current state/],
    [413, /too large/],
    [500, /server had a problem/],
    [503, /server had a problem/],
  ])("explains HTTP %i in plain words without showing server details", async (status, pattern) => {
    mockBackend(() => jsonResponse(status, { detail: "RAW-SERVER-DETAIL at com.contractiq.Foo" }));

    const error = await failure(request("GET", "/x"));

    expect(error.message).toMatch(pattern);
    expect(error.message).not.toContain("RAW-SERVER-DETAIL");
    expect(error.status).toBe(status);
  });

  it("explains a network failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const error = await failure(request("GET", "/x"));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.message).toMatch(/Could not reach the server/);
  });

  it("gives up with a clear message when the server does not answer within 30 seconds", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      })),
    );

    const pending = failure(request("GET", "/x"));
    await vi.advanceTimersByTimeAsync(30_000);
    const error = await pending;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toMatch(/took too long/);
  });

  it("lets a caller cancel a request quietly", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      })),
    );
    const controller = new AbortController();

    const pending = request("GET", "/x", { signal: controller.signal }).catch((e) => e);
    controller.abort();
    const error = await pending;

    expect(error).not.toBeInstanceOf(ApiError);
    expect((error as DOMException).name).toBe("AbortError");
  });

  it("reports an answer that is not JSON", async () => {
    mockBackend(() => new Response("<html>nope</html>", { status: 200 }));

    await expect(request("GET", "/x")).rejects.toThrow(/could not read/);
  });

  it("never writes the token to localStorage, sessionStorage or cookies", async () => {
    mockBackend(() => jsonResponse(200, { token: fakeToken({ role: "UPLOADER" }), tokenType: "Bearer", expiresInSeconds: 1800 }));

    const answer = await login("uploader", "secret-password");
    setToken(answer.token);
    await request("GET", "/api/v1/contracts");

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe("");
  });
});
