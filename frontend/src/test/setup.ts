import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";
import { setToken, setUnauthorizedHandler } from "../api/client";

// Testing Library only knows how to advance fake timers when it detects Jest. This tiny stand-in lets
// its async helpers (findBy*, waitFor, user-event) work under Vitest's fake timers instead of hanging.
(globalThis as { jest?: unknown }).jest = { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) };

beforeEach(() => {
  // The token and the 401 handler live in module state; start every test clean.
  setToken(null);
  setUnauthorizedHandler(null);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
