import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { contractDetail, listPage, loginRoute, on, onPrefix, summaryOf } from "../test/fixtures";
import { jsonResponse, mockBackend } from "../test/helpers";
import { renderApp, signIn } from "../test/render";

const listRoute = onPrefix("GET", "/api/v1/contracts?", () => jsonResponse(200, listPage([summaryOf(contractDetail())])));

describe("login flow", () => {
  it("shows a labelled form, and a successful sign-in lands on the contract list", async () => {
    mockBackend(loginRoute("REVIEWER"), listRoute);
    renderApp("/login");

    await signIn(userEvent.setup(), "rita.reviewer");

    expect(await screen.findByRole("heading", { name: "Contracts" })).toBeTruthy();
    expect(screen.getByText("rita.reviewer")).toBeTruthy();
    expect(screen.getByText("REVIEWER")).toBeTruthy();
    expect(await screen.findByRole("link", { name: "Office supplies" })).toBeTruthy();
  });

  it("sends the credentials to the login endpoint and then the token on later requests", async () => {
    const fetchMock = mockBackend(loginRoute("UPLOADER"), listRoute);
    renderApp("/login");

    await signIn(userEvent.setup(), "ula.uploader", "pw-123");
    await screen.findByRole("heading", { name: "Contracts" });

    const login = fetchMock.mock.calls.find((call) => String(call[0]).endsWith("/api/v1/auth/login"))!;
    expect(JSON.parse(String(login[1]?.body))).toEqual({ username: "ula.uploader", password: "pw-123" });
    const list = fetchMock.mock.calls.find((call) => String(call[0]).includes("/api/v1/contracts?"))!;
    expect(new Headers(list[1]?.headers).get("Authorization")).toMatch(/^Bearer .+\..+\..+$/);
  });

  it("keeps the password hidden and the token out of browser storage", async () => {
    mockBackend(loginRoute("UPLOADER"), listRoute);
    renderApp("/login");

    expect((screen.getByLabelText("Password") as HTMLInputElement).type).toBe("password");
    await signIn();
    await screen.findByRole("heading", { name: "Contracts" });

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe("");
    expect(document.body.innerHTML).not.toContain("not-a-real-password");
  });

  it("shows a clear error for a wrong password, clears the password and stays on the login page", async () => {
    mockBackend(on("POST", "/api/v1/auth/login", () => jsonResponse(401, { title: "Unauthorized" })));
    renderApp("/login");

    await signIn(userEvent.setup(), "someone", "wrong-password");

    expect((await screen.findByRole("alert")).textContent).toContain("Invalid username or password.");
    expect((screen.getByLabelText("Password") as HTMLInputElement).value).toBe("");
    expect(screen.queryByText(/session has expired/i)).toBeNull();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeTruthy();
  });

  it("tells the person to wait when the backend has locked the sign-in (429), and does not call it a wrong password", async () => {
    mockBackend(on("POST", "/api/v1/auth/login", () =>
      new Response(JSON.stringify({ status: 429, title: "Too Many Requests" }), {
        status: 429,
        headers: { "Content-Type": "application/problem+json", "Retry-After": "600" },
      })));
    renderApp("/login");

    await signIn(userEvent.setup(), "someone", "any-password");

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("Too many sign-in attempts, try again later.");
    expect(alert.textContent).not.toMatch(/Invalid username or password/);
    expect(screen.queryByText(/session has expired/i)).toBeNull();
    expect((screen.getByLabelText("Password") as HTMLInputElement).value).toBe("");
    expect(screen.getByRole("form", { name: "Sign in" })).toBeTruthy();
  });

  it("explains a server that cannot be reached", async () => {
    mockBackend(() => { throw new TypeError("Failed to fetch"); });
    renderApp("/login");

    await signIn();

    expect((await screen.findByRole("alert")).textContent).toMatch(/Could not reach the server/);
  });

  it("refuses a token whose role it does not understand", async () => {
    mockBackend(on("POST", "/api/v1/auth/login", () => jsonResponse(200, { token: "not.a-real.token", tokenType: "Bearer", expiresInSeconds: 1 })));
    renderApp("/login");

    await signIn();

    expect((await screen.findByRole("alert")).textContent).toMatch(/could not understand/);
  });

  it("sends a visitor who is not signed in to the login page, then back to the page they wanted", async () => {
    mockBackend(
      loginRoute("REVIEWER"),
      on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail())),
      on("GET", "/api/v1/contracts/7/analysis", () => jsonResponse(404, {})),
    );
    renderApp("/contracts/7");

    expect(await screen.findByRole("form", { name: "Sign in" })).toBeTruthy();
    await signIn();

    expect(await screen.findByRole("heading", { name: "Office supplies" })).toBeTruthy();
  });

  it("ignores a 'return to' address that points outside the app", async () => {
    mockBackend(loginRoute("UPLOADER"), listRoute);
    renderApp({ pathname: "/login", state: { from: "//evil.example/steal" } });

    await signIn();

    expect(await screen.findByRole("heading", { name: "Contracts" })).toBeTruthy();
  });

  it("signing out returns to the login page and protects the pages again", async () => {
    mockBackend(loginRoute("UPLOADER"), listRoute);
    renderApp("/login");
    const user = await signIn();
    await screen.findByRole("heading", { name: "Contracts" });

    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("form", { name: "Sign in" })).toBeTruthy();
    expect(screen.queryByText("tester")).toBeNull();
    // A new visit to a protected page is turned away: the token is gone.
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Contracts" })).toBeNull());
  });

  it("after a sign-out, whoever signs in next starts at the list, not on the previous user's page", async () => {
    mockBackend(
      loginRoute("UPLOADER"),
      listRoute,
      on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail({ status: "UPLOADED" }))),
    );
    renderApp("/login");
    const user = await signIn();
    await user.click(await screen.findByRole("link", { name: "Office supplies" }));
    await screen.findByRole("heading", { name: "Office supplies" });

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    await signIn(user, "someone.else");

    expect(await screen.findByRole("heading", { name: "Contracts" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Office supplies" })).toBeNull();
  });

  it("disables the button while signing in so it cannot be sent twice", async () => {
    let release: (response: Response) => void = () => undefined;
    mockBackend(on("POST", "/api/v1/auth/login", () => new Promise<Response>((resolve) => { release = resolve; })));
    renderApp("/login");
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Username"), "u");
    await user.type(screen.getByLabelText("Password"), "p");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const busy = await screen.findByRole("button", { name: "Signing in…" });
    expect((busy as HTMLButtonElement).disabled).toBe(true);
    release(jsonResponse(401, {}));
    await screen.findByRole("alert");
  });
});
