import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Role } from "../api/types";
import {
  analysisResponse,
  contractDetail,
  listPage,
  loginRoute,
  on,
  onPrefix,
  summaryOf,
} from "../test/fixtures";
import { jsonResponse, mockBackend, type Route } from "../test/helpers";
import { renderApp, signIn } from "../test/render";

interface Options {
  status?: Parameters<typeof contractDetail>[0] extends infer T ? (T extends { status?: infer S } ? S : never) : never;
  withAnalysis?: boolean;
  extraRoutes?: Route[];
}

/** Opens the detail page of contract 7 as a signed-in user of the given role. */
async function openDetail(role: Role, { status = "ANALYZED", withAnalysis = true, extraRoutes = [] }: Options = {}) {
  const fetchMock = mockBackend(
    loginRoute(role),
    ...extraRoutes,
    on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail({ status }))),
    on("GET", "/api/v1/contracts/7/analysis", () =>
      withAnalysis ? jsonResponse(200, analysisResponse()) : jsonResponse(404, {}),
    ),
  );
  renderApp("/contracts/7");
  const user = await signIn();
  await screen.findByRole("heading", { name: "Office supplies" });
  return { user, fetchMock };
}

const button = (name: string) => screen.queryByRole("button", { name }) as HTMLButtonElement | null;

describe("buttons by role on the contract page", () => {
  describe("an uploaded contract", () => {
    it("lets an UPLOADER analyze it, and shows no approve or reject buttons", async () => {
      await openDetail("UPLOADER", { status: "UPLOADED", withAnalysis: false });

      expect(button("Analyze with AI")).not.toBeNull();
      expect(button("Approve")).toBeNull();
      expect(button("Reject")).toBeNull();
      expect(screen.getByText(/Approving or rejecting is done by reviewers/)).toBeTruthy();
    });

    it("does not let a REVIEWER analyze it, and explains why the review buttons are off", async () => {
      await openDetail("REVIEWER", { status: "UPLOADED", withAnalysis: false });

      expect(button("Analyze with AI")).toBeNull();
      expect(button("Approve")!.disabled).toBe(true);
      expect(button("Reject")!.disabled).toBe(true);
      expect(screen.getByText(/An uploader needs to start the analysis first/)).toBeTruthy();
    });

    it("gives an ADMIN everything", async () => {
      await openDetail("ADMIN", { status: "UPLOADED", withAnalysis: false });

      expect(button("Analyze with AI")).not.toBeNull();
      expect(button("Approve")).not.toBeNull();
      expect(button("Reject")).not.toBeNull();
    });
  });

  describe("an analyzed contract", () => {
    it("offers an UPLOADER only 'Analyze again'", async () => {
      await openDetail("UPLOADER");

      expect(button("Analyze again")).not.toBeNull();
      expect(button("Approve")).toBeNull();
      expect(button("Reject")).toBeNull();
    });

    it("offers a REVIEWER approve and reject but no analyze button", async () => {
      await openDetail("REVIEWER");

      expect(button("Approve")).not.toBeNull();
      expect(button("Reject")).not.toBeNull();
      expect(button("Analyze again")).toBeNull();
      expect(button("Analyze with AI")).toBeNull();
    });

    it("keeps approve and reject off until a real comment is written", async () => {
      const { user } = await openDetail("REVIEWER");
      const comment = screen.getByLabelText("Comment (required)");

      expect(button("Approve")!.disabled).toBe(true);
      expect(button("Reject")!.disabled).toBe(true);
      await user.type(comment, "   ");
      expect(button("Approve")!.disabled).toBe(true);
      expect(button("Reject")!.disabled).toBe(true);
      await user.type(comment, "Liability clause is unacceptable.");
      expect(button("Approve")!.disabled).toBe(false);
      expect(button("Reject")!.disabled).toBe(false);
    });

    it("sends a rejection with the comment, then shows the recorded decision", async () => {
      let sent: unknown;
      const { user, fetchMock } = await openDetail("REVIEWER", {
        extraRoutes: [
          (request) => {
            if (request.method === "POST" && request.path === "/api/v1/contracts/7/review") {
              sent = request.body;
              return jsonResponse(200, {
                id: 1, contractId: 7, reviewerId: 2, decision: "REJECTED", comment: "Too risky.",
                createdAt: "2026-01-02T09:00:00Z", contractStatus: "REJECTED",
              });
            }
            return undefined;
          },
        ],
      });

      await user.type(screen.getByLabelText("Comment (required)"), "  Too risky.  ");
      await user.click(button("Reject")!);

      const recorded = await screen.findByRole("status", { name: "Decision recorded" });
      expect(sent).toEqual({ decision: "REJECTED", comment: "Too risky." });
      expect(within(recorded).getByText(/You rejected this contract/)).toBeTruthy();
      expect(within(recorded).getByText(/Too risky\./)).toBeTruthy();
      expect(button("Approve")).toBeNull();
      expect(fetchMock.mock.calls.filter((c) => String(c[0]).endsWith("/review"))).toHaveLength(1);
    });

    it("sends an approval the same way", async () => {
      let sent: unknown;
      const { user } = await openDetail("ADMIN", {
        extraRoutes: [
          (request) => {
            if (request.method === "POST" && request.path === "/api/v1/contracts/7/review") {
              sent = request.body;
              return jsonResponse(200, {
                id: 2, contractId: 7, reviewerId: 3, decision: "APPROVED", comment: "Fine.",
                createdAt: "2026-01-02T09:00:00Z", contractStatus: "APPROVED",
              });
            }
            return undefined;
          },
        ],
      });

      await user.type(screen.getByLabelText("Comment (required)"), "Fine.");
      await user.click(button("Approve")!);

      await screen.findByRole("status", { name: "Decision recorded" });
      expect(sent).toEqual({ decision: "APPROVED", comment: "Fine." });
    });

    it("shows the reason and reloads the contract when the backend says it has changed (409)", async () => {
      const { user, fetchMock } = await openDetail("REVIEWER", {
        extraRoutes: [on("POST", "/api/v1/contracts/7/review", () => jsonResponse(409, { title: "Conflict" }))],
      });

      await user.type(screen.getByLabelText("Comment (required)"), "Looks fine.");
      await user.click(button("Approve")!);

      expect((await screen.findByRole("alert")).textContent).toMatch(/not possible in the contract's current state/);
      const reloads = fetchMock.mock.calls.filter((c) => String(c[0]).endsWith("/api/v1/contracts/7") && (c[1]?.method ?? "GET") === "GET");
      expect(reloads.length).toBeGreaterThanOrEqual(2);
    });
  });

  describe("other statuses", () => {
    it("lets a REVIEWER decide a contract that needs manual review", async () => {
      const { user } = await openDetail("REVIEWER", { status: "NEEDS_MANUAL_REVIEW", withAnalysis: false });

      await user.type(screen.getByLabelText("Comment (required)"), "Read it myself.");

      expect(button("Approve")!.disabled).toBe(false);
    });

    it("does not allow a decided contract to be decided or analyzed again", async () => {
      await openDetail("ADMIN", { status: "APPROVED" });

      expect(button("Approve")!.disabled).toBe(true);
      expect(button("Analyze again")).toBeNull();
      expect(screen.getByText(/already been decided/)).toBeTruthy();
      expect(screen.getByText(/has been approved by a reviewer/)).toBeTruthy();
    });

    it("turns the review buttons off while the contract is being analyzed", async () => {
      await openDetail("REVIEWER", { status: "ANALYZING", withAnalysis: false });

      expect(button("Approve")!.disabled).toBe(true);
      expect(screen.getByText(/being analyzed/)).toBeTruthy();
    });
  });
});

describe("buttons by role on the list and upload pages", () => {
  const listRoute = onPrefix("GET", "/api/v1/contracts?", () => jsonResponse(200, listPage([summaryOf(contractDetail())])));

  it.each([
    ["UPLOADER", true],
    ["REVIEWER", false],
    ["ADMIN", true],
  ] as const)("%s sees the Upload links: %s", async (role, expected) => {
    mockBackend(loginRoute(role), listRoute);
    renderApp("/contracts");
    await signIn();
    await screen.findByRole("link", { name: "Office supplies" });

    const links = screen.queryAllByRole("link", { name: "Upload contract" });
    expect(links.length > 0).toBe(expected);
    expect(links.length).toBe(expected ? 2 : 0); // one in the header, one beside the title
  });

  it("tells a REVIEWER who opens the upload page that their role cannot upload", async () => {
    mockBackend(loginRoute("REVIEWER"));
    renderApp("/contracts/new");
    await signIn();

    expect(await screen.findByText(/Your role \(REVIEWER\) cannot upload contracts/)).toBeTruthy();
    expect(screen.queryByRole("form", { name: "Upload contract" })).toBeNull();
  });
});

describe("uploading a contract", () => {
  it("keeps Upload off until every field is filled, sends the trimmed values and opens the new contract", async () => {
    let sent: unknown;
    mockBackend(
      loginRoute("UPLOADER"),
      (request) => {
        if (request.method === "POST" && request.path === "/api/v1/contracts") {
          sent = request.body;
          return jsonResponse(201, summaryOf(contractDetail({ id: 9, title: "New deal" })));
        }
        return undefined;
      },
      on("GET", "/api/v1/contracts/9", () => jsonResponse(200, contractDetail({ id: 9, title: "New deal", status: "UPLOADED" }))),
    );
    renderApp("/contracts/new");
    const user = await signIn();
    await screen.findByRole("form", { name: "Upload contract" });

    const upload = screen.getByRole("button", { name: "Upload" }) as HTMLButtonElement;
    expect(upload.disabled).toBe(true);
    await user.type(screen.getByLabelText("Title"), "  New deal ");
    await user.type(screen.getByLabelText("Vendor"), "Acme Ltd");
    expect(upload.disabled).toBe(true);
    await user.click(screen.getByLabelText("Contract text"));
    await user.paste("The vendor shall deliver goods.");
    expect(upload.disabled).toBe(false);
    await user.click(upload);

    expect(await screen.findByRole("heading", { name: "New deal" })).toBeTruthy();
    expect(sent).toEqual({ title: "New deal", vendorName: "Acme Ltd", contractText: "The vendor shall deliver goods." });
  });

  it("shows the backend's validation message", async () => {
    mockBackend(
      loginRoute("UPLOADER"),
      on("POST", "/api/v1/contracts", () => jsonResponse(400, { errors: [{ field: "contractText", message: "size must be between 0 and 200000" }] })),
    );
    renderApp("/contracts/new");
    const user = await signIn();
    await user.type(await screen.findByLabelText("Title"), "T");
    await user.type(screen.getByLabelText("Vendor"), "V");
    await user.click(screen.getByLabelText("Contract text"));
    await user.paste("x");

    await user.click(screen.getByRole("button", { name: "Upload" }));

    expect((await screen.findByRole("alert")).textContent).toContain("contractText: size must be between 0 and 200000");
  });

  it("limits the lengths to what the backend accepts", async () => {
    mockBackend(loginRoute("UPLOADER"));
    renderApp("/contracts/new");
    await signIn();

    expect((await screen.findByLabelText("Title") as HTMLInputElement).maxLength).toBe(300);
    expect((screen.getByLabelText("Vendor") as HTMLInputElement).maxLength).toBe(300);
    expect((screen.getByLabelText("Contract text") as HTMLTextAreaElement).maxLength).toBe(200_000);
  });
});

describe("the contract list", () => {
  it("filters by status through the API and shows an empty message", async () => {
    const fetchMock = mockBackend(
      loginRoute("UPLOADER"),
      onPrefix("GET", "/api/v1/contracts?status=APPROVED", () => jsonResponse(200, listPage([]))),
      onPrefix("GET", "/api/v1/contracts?", () => jsonResponse(200, listPage([summaryOf(contractDetail())]))),
    );
    renderApp("/contracts");
    const user = await signIn();
    await screen.findByRole("link", { name: "Office supplies" });

    await user.selectOptions(screen.getByLabelText("Status"), "APPROVED");

    expect(await screen.findByText(/No contracts have the status “Approved”/)).toBeTruthy();
    expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("status=APPROVED"))).toBe(true);
  });

  it("shows loading, empty and error states", async () => {
    let fail = true;
    mockBackend(
      loginRoute("UPLOADER"),
      onPrefix("GET", "/api/v1/contracts?", () => (fail ? jsonResponse(500, {}) : jsonResponse(200, listPage([])))),
    );
    renderApp("/contracts");
    const user = await signIn();

    expect(await screen.findByRole("alert")).toBeTruthy();
    fail = false;
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/There are no contracts yet\. Upload the first one/)).toBeTruthy();
  });

  it("shows each status as text with a symbol, not just a color", async () => {
    mockBackend(
      loginRoute("REVIEWER"),
      onPrefix("GET", "/api/v1/contracts?", () =>
        jsonResponse(200, listPage([
          summaryOf(contractDetail({ id: 1, title: "A", status: "APPROVED" })),
          summaryOf(contractDetail({ id: 2, title: "B", status: "NEEDS_MANUAL_REVIEW" })),
        ]))),
    );
    renderApp("/contracts");
    await signIn();
    await screen.findByRole("link", { name: "A" });

    const table = within(screen.getByRole("table"));
    expect(table.getByText(/Approved/).textContent).toContain("✓");
    expect(table.getByText(/Needs manual review/).textContent).toContain("!");
  });
});

// Keeps the helper types honest: the user-event instance is what signIn returns.
export type _Unused = ReturnType<typeof userEvent.setup>;
