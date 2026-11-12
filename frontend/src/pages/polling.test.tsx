import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ContractStatus } from "../api/types";
import { analysisResponse, contractDetail, loginRoute, on } from "../test/fixtures";
import { jsonResponse, mockBackend, type Route } from "../test/helpers";
import { renderApp, signIn } from "../test/render";

/** Lets pending promises and any timers due right now run. */
async function flush() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

interface Scenario {
  /** Status the contract has when the page first opens. */
  initial?: ContractStatus;
  /** What each poll returns, in order; the last entry repeats. Each is a status or an HTTP error code. */
  polls: (ContractStatus | number)[];
  errorCode?: string | null;
  analyzeStatus?: number;
}

/** Opens contract 7 as an UPLOADER with a fake backend, then switches to fake timers. */
async function openContract({ initial = "UPLOADED", polls, errorCode = null, analyzeStatus = 202 }: Scenario) {
  let started = initial === "ANALYZING";
  let pollIndex = 0;
  let finished: ContractStatus | null = null;
  const routes: Route[] = [
    loginRoute("UPLOADER"),
    on("POST", "/api/v1/contracts/7/analyze", () => {
      if (analyzeStatus !== 202) return jsonResponse(analyzeStatus, {});
      started = true;
      return jsonResponse(202, { contractId: 7, status: "ANALYZING" });
    }),
    on("GET", "/api/v1/contracts/7", () => {
      if (!started) return jsonResponse(200, contractDetail({ status: initial }));
      const next = polls[Math.min(pollIndex, polls.length - 1)];
      pollIndex += 1;
      if (typeof next === "number") return jsonResponse(next, {});
      if (next !== "ANALYZING") finished = next;
      return jsonResponse(200, contractDetail({ status: next, analysisErrorCode: next === "NEEDS_MANUAL_REVIEW" ? errorCode : null }));
    }),
    on("GET", "/api/v1/contracts/7/analysis", () =>
      finished === "ANALYZED" ? jsonResponse(200, analysisResponse()) : jsonResponse(404, {}),
    ),
  ];
  const fetchMock = mockBackend(...routes);
  vi.useFakeTimers(); // before anything renders, so every timer (and Date) is under the test's control
  const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
  const view = renderApp("/contracts/7");
  await signIn(user);
  await screen.findByRole("heading", { name: "Office supplies" });
  const contractGets = () =>
    fetchMock.mock.calls.filter((c) => String(c[0]).endsWith("/api/v1/contracts/7") && (c[1]?.method ?? "GET") === "GET").length;
  return { user, view, fetchMock, contractGets };
}

async function clickAnalyze(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Analyze with AI" }));
  await flush();
}

describe("polling after Analyze", () => {
  it("checks the status every 2 seconds until the analysis finishes, then stops and shows the result", async () => {
    const { user, contractGets } = await openContract({ polls: ["ANALYZING", "ANALYZING", "ANALYZED"] });
    expect(contractGets()).toBe(1); // the page load

    await clickAnalyze(user);
    expect(screen.getByRole("status", { name: "" }).textContent).toContain("The AI is analyzing this contract");
    expect(contractGets()).toBe(1); // nothing is polled until 2 seconds have passed

    await advance(1999);
    expect(contractGets()).toBe(1);
    await advance(1);
    expect(contractGets()).toBe(2); // first poll: still ANALYZING
    await advance(2000);
    expect(contractGets()).toBe(3); // second poll: still ANALYZING
    expect(screen.queryByRole("heading", { name: /Risk flags/ })).toBeNull();
    await advance(2000);
    expect(contractGets()).toBe(4); // third poll: ANALYZED

    expect(screen.getByRole("heading", { name: /Risk flags/ })).toBeTruthy();
    expect(screen.getByText("A supply contract between Acme Ltd and City Council.")).toBeTruthy();
    expect(screen.queryByText(/The AI is analyzing this contract/)).toBeNull();

    // Polling has stopped for good.
    await advance(60_000);
    expect(contractGets()).toBe(4);
  });

  it("stops when the analysis fails and explains the error code in plain words", async () => {
    const { user, contractGets } = await openContract({
      polls: ["ANALYZING", "NEEDS_MANUAL_REVIEW"],
      errorCode: "ANALYSIS_TIMEOUT",
    });

    await clickAnalyze(user);
    await advance(4000);

    expect(contractGets()).toBe(3);
    expect(screen.getByText("The AI could not finish this analysis.")).toBeTruthy();
    expect(screen.getByText("The AI service took too long to answer.")).toBeTruthy();
    expect(screen.getByText(/Reason code: ANALYSIS_TIMEOUT/)).toBeTruthy();
    expect(screen.getByText(/A person needs to read this contract and decide/)).toBeTruthy();
    await advance(60_000);
    expect(contractGets()).toBe(3);
  });

  it("starts polling by itself when the contract is already being analyzed", async () => {
    // The first scripted answer is used by the page load itself; the polls get the rest.
    const { contractGets } = await openContract({ initial: "ANALYZING", polls: ["ANALYZING", "ANALYZING", "ANALYZED"] });

    expect(contractGets()).toBe(1);
    await advance(2000);
    expect(contractGets()).toBe(2);
    await advance(2000);
    expect(contractGets()).toBe(3);
    await advance(60_000);
    expect(contractGets()).toBe(3);
  });

  it("stops when the page is left (the component unmounts)", async () => {
    const { user, view, contractGets } = await openContract({ polls: ["ANALYZING"] });
    await clickAnalyze(user);
    await advance(2000);
    expect(contractGets()).toBe(2);

    view.unmount();
    await advance(60_000);

    expect(contractGets()).toBe(2);
  });

  it("stops and returns to the login page when a poll gets a 401", async () => {
    const { user, contractGets } = await openContract({ polls: ["ANALYZING", 401] });
    await clickAnalyze(user);
    await advance(2000);
    expect(contractGets()).toBe(2);

    await advance(2000);

    expect(contractGets()).toBe(3);
    expect(screen.getByRole("form", { name: "Sign in" })).toBeTruthy();
    expect(screen.getByText("Your session has expired. Please sign in again.")).toBeTruthy();
    await advance(60_000);
    expect(contractGets()).toBe(3);
  });

  it("gives up after 5 failed checks in a row, says so, and can be restarted", async () => {
    const { user, contractGets } = await openContract({ polls: [500, 500, 500, 500, 500, "ANALYZING", "ANALYZED"] });
    await clickAnalyze(user);

    await advance(2000);
    expect(screen.getByText(/Having trouble checking the status/)).toBeTruthy();
    await advance(8000); // polls 2 to 5
    expect(contractGets()).toBe(6);
    expect(screen.getByText(/Automatic checking has stopped because the status could not be read/)).toBeTruthy();
    await advance(60_000);
    expect(contractGets()).toBe(6); // stopped

    await user.click(screen.getByRole("button", { name: "Check again" }));
    await flush();
    expect(contractGets()).toBe(7); // reloaded the contract
    await advance(2000);
    expect(contractGets()).toBe(8); // polling is running again
    await advance(2000);
    expect(screen.getByRole("heading", { name: /Risk flags/ })).toBeTruthy();
  });

  it("a good answer resets the count of failed checks", async () => {
    const { user, contractGets } = await openContract({
      polls: [500, 500, 500, 500, "ANALYZING", 500, 500, 500, 500, "ANALYZED"],
    });
    await clickAnalyze(user);

    await advance(2000 * 10);

    // Never 5 failures in a row, so polling continued all the way to the result.
    expect(contractGets()).toBe(11);
    expect(screen.getByRole("heading", { name: /Risk flags/ })).toBeTruthy();
  });

  it("stops after 5 minutes and says the analysis is taking longer than expected", async () => {
    const { user, contractGets } = await openContract({ polls: ["ANALYZING"] });
    await clickAnalyze(user);

    await advance(5 * 60 * 1000 + 2000);
    const afterCap = contractGets();

    expect(screen.getByText(/taking longer than expected/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Check again" })).toBeTruthy();
    expect(afterCap).toBeLessThanOrEqual(1 + 151);
    await advance(60_000);
    expect(contractGets()).toBe(afterCap);
  });

  it("never has two checks in flight at once", async () => {
    let started = false;
    let inFlight = 0;
    let maxInFlight = 0;
    mockBackend(
      loginRoute("UPLOADER"),
      on("POST", "/api/v1/contracts/7/analyze", () => {
        started = true;
        return jsonResponse(202, { contractId: 7, status: "ANALYZING" });
      }),
      on("GET", "/api/v1/contracts/7", () => {
        if (!started) return jsonResponse(200, contractDetail({ status: "UPLOADED" }));
        inFlight += 1;
        maxInFlight = Math.max(maxInFlight, inFlight);
        // Each answer takes 5 seconds, longer than the 2 second interval.
        return new Promise<Response>((resolve) => {
          setTimeout(() => {
            inFlight -= 1;
            resolve(jsonResponse(200, contractDetail({ status: "ANALYZING" })));
          }, 5000);
        });
      }),
    );
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    renderApp("/contracts/7");
    await signIn(user);
    await screen.findByRole("heading", { name: "Office supplies" });

    await clickAnalyze(user);
    await advance(30_000);

    expect(maxInFlight).toBe(1);
  });

  it("shows the reason and does not poll when the backend refuses to start the analysis", async () => {
    const { user, contractGets } = await openContract({ polls: ["ANALYZING"], analyzeStatus: 409 });

    await clickAnalyze(user);
    await advance(10_000);

    expect(screen.getByRole("alert").textContent).toMatch(/already being analyzed|current state/);
    expect(screen.queryByText(/The AI is analyzing this contract/)).toBeNull();
    // 1 for the page load and 1 for the reload after the conflict; no polling.
    expect(contractGets()).toBe(2);
  });
});
