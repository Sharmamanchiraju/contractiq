import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Role } from "../api/types";
import { analysisResponse, contractDetail, listPage, loginRoute, on, onPrefix, summaryOf } from "../test/fixtures";
import { jsonResponse, mockBackend, type Route } from "../test/helpers";
import { renderApp, signIn } from "../test/render";

const SESSION_EXPIRED = "Your session has expired. Please sign in again.";

async function expectLoginPageWithExpiredNotice() {
  expect(await screen.findByRole("form", { name: "Sign in" })).toBeTruthy();
  expect(screen.getByRole("status").textContent).toBe(SESSION_EXPIRED);
}

function detailRoutes(role: Role, ...overrides: Route[]) {
  return [
    ...overrides,
    loginRoute(role),
    on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail({ status: role === "UPLOADER" ? "UPLOADED" : "ANALYZED" }))),
    on("GET", "/api/v1/contracts/7/analysis", () =>
      role === "UPLOADER" ? jsonResponse(404, {}) : jsonResponse(200, analysisResponse())),
  ];
}

describe("a 401 from the backend returns to the login page", () => {
  it("when the contract list is loaded", async () => {
    mockBackend(loginRoute("UPLOADER"), onPrefix("GET", "/api/v1/contracts?", () => jsonResponse(401, { title: "Unauthorized" })));
    renderApp("/contracts");

    await signIn();

    await expectLoginPageWithExpiredNotice();
    expect(screen.queryByRole("heading", { name: "Contracts" })).toBeNull();
  });

  it("when a contract is opened", async () => {
    mockBackend(loginRoute("UPLOADER"), on("GET", "/api/v1/contracts/7", () => jsonResponse(401, {})));
    renderApp("/contracts/7");

    await signIn();

    await expectLoginPageWithExpiredNotice();
  });

  it("when the analysis is loaded", async () => {
    mockBackend(
      loginRoute("REVIEWER"),
      on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail())),
      on("GET", "/api/v1/contracts/7/analysis", () => jsonResponse(401, {})),
    );
    renderApp("/contracts/7");

    await signIn();

    await expectLoginPageWithExpiredNotice();
  });

  it("when Analyze is clicked", async () => {
    mockBackend(...detailRoutes("UPLOADER", on("POST", "/api/v1/contracts/7/analyze", () => jsonResponse(401, {}))));
    renderApp("/contracts/7");
    const user = await signIn();

    await user.click(await screen.findByRole("button", { name: "Analyze with AI" }));

    await expectLoginPageWithExpiredNotice();
  });

  it("when a decision is submitted", async () => {
    mockBackend(...detailRoutes("REVIEWER", on("POST", "/api/v1/contracts/7/review", () => jsonResponse(401, {}))));
    renderApp("/contracts/7");
    const user = await signIn();
    await screen.findByRole("heading", { name: /Risk flags/ });

    await user.type(screen.getByLabelText("Comment (required)"), "Fine.");
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await expectLoginPageWithExpiredNotice();
  });

  it("when a contract is uploaded", async () => {
    mockBackend(loginRoute("UPLOADER"), on("POST", "/api/v1/contracts", () => jsonResponse(401, {})));
    renderApp("/contracts/new");
    const user = await signIn();
    await user.type(await screen.findByLabelText("Title"), "T");
    await user.type(screen.getByLabelText("Vendor"), "V");
    await user.click(screen.getByLabelText("Contract text"));
    await user.paste("text");

    await user.click(screen.getByRole("button", { name: "Upload" }));

    await expectLoginPageWithExpiredNotice();
  });

  it("and the old token is gone: the next sign-in sends no Authorization header, and signing in again works", async () => {
    let expired = true;
    const fetchMock = mockBackend(
      loginRoute("UPLOADER"),
      onPrefix("GET", "/api/v1/contracts?", () =>
        expired ? jsonResponse(401, {}) : jsonResponse(200, listPage([summaryOf(contractDetail())]))),
    );
    renderApp("/contracts");
    const user = await signIn();
    await expectLoginPageWithExpiredNotice();

    expired = false;
    await signIn(user, "tester", "pw-again");

    expect(await screen.findByRole("link", { name: "Office supplies" })).toBeTruthy();
    expect(screen.queryByText(SESSION_EXPIRED)).toBeNull();
    const logins = fetchMock.mock.calls.filter((c) => String(c[0]).endsWith("/api/v1/auth/login"));
    expect(logins).toHaveLength(2);
    expect(new Headers(logins[1][1]?.headers).get("Authorization")).toBeNull();
  });

  it("but a 401 from the login itself is a wrong password, not an expired session", async () => {
    mockBackend(on("POST", "/api/v1/auth/login", () => jsonResponse(401, {})));
    renderApp("/login");

    await signIn();

    expect((await screen.findByRole("alert")).textContent).toContain("Invalid username or password.");
    expect(screen.queryByText(SESSION_EXPIRED)).toBeNull();
  });

  it("and other errors (403, 404, 500) do not sign the user out", async () => {
    for (const status of [403, 404, 500]) {
      mockBackend(loginRoute("UPLOADER"), on("GET", "/api/v1/contracts/7", () => jsonResponse(status, {})));
      const view = renderApp("/contracts/7");
      await signIn(userEvent.setup());

      expect(await screen.findByRole("alert")).toBeTruthy();
      expect(screen.queryByRole("form", { name: "Sign in" })).toBeNull();
      view.unmount();
    }
  });
});
