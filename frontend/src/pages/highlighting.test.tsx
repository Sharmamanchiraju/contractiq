import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AnalysisResponse } from "../api/types";
import { CONTRACT_TEXT, LIABILITY_QUOTE, PAYMENT_QUOTE, analysisResponse, contractDetail, loginRoute, on } from "../test/fixtures";
import { jsonResponse, mockBackend } from "../test/helpers";
import { renderApp, signIn } from "../test/render";

async function openWith(text: string, analysis: AnalysisResponse, title = "Office supplies") {
  mockBackend(
    loginRoute("REVIEWER"),
    on("GET", "/api/v1/contracts/7", () => jsonResponse(200, contractDetail({ contractText: text, title }))),
    on("GET", "/api/v1/contracts/7/analysis", () => jsonResponse(200, analysis)),
  );
  renderApp("/contracts/7");
  const user = await signIn();
  await screen.findByRole("heading", { name: title });
  await screen.findByRole("heading", { name: /Risk flags/ });
  return user;
}

const textRegion = () => screen.getByRole("region", { name: /Original contract text/ });
const marks = () => Array.from(textRegion().querySelectorAll("mark.quote"));

afterEach(() => {
  delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
});

describe("highlighting risk-flag quotes in the original text", () => {
  it("highlights each quote exactly where it appears in the contract", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse());

    const found = marks();
    expect(found).toHaveLength(2);
    expect(found[0].textContent).toBe("1" + LIABILITY_QUOTE);
    expect(found[1].textContent).toBe("2" + PAYMENT_QUOTE);
    // The text around the quotes is still there, in order, and nothing was lost: the highlighted
    // pieces only add the flag numbers.
    const before = "This agreement is between Acme Ltd and City Council.\n";
    expect(textRegion().textContent).toBe(before + "1" + LIABILITY_QUOTE + "\n" + "2" + PAYMENT_QUOTE);
  });

  it("numbers each highlight and colors it by severity", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse());

    const [first, second] = marks();
    expect(first.classList.contains("severity-high")).toBe(true);
    expect(second.classList.contains("severity-low")).toBe(true);
    expect(within(first as HTMLElement).getByLabelText("Risk flag 1").textContent).toBe("1");
    expect(within(second as HTMLElement).getByLabelText("Risk flag 2").textContent).toBe("2");
  });

  it("finds a quote even when the contract has different line breaks and spacing", async () => {
    const text = "Intro.\nThe vendor   accepts unlimited\nliability   for late delivery.\nEnd.";
    await openWith(text, analysisResponse({
      riskFlags: [{ clause: "Liability", severity: "HIGH", reason: "r", quote: LIABILITY_QUOTE }],
    }));

    expect(marks()).toHaveLength(1);
    expect(marks()[0].textContent).toBe("1The vendor   accepts unlimited\nliability   for late delivery.");
  });

  it("selects a flag and scrolls its quote into view when 'Show in contract' is pressed", async () => {
    const scroll = vi.fn();
    (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView = scroll;
    const user = await openWith(CONTRACT_TEXT, analysisResponse());
    const buttons = screen.getAllByRole("button", { name: "Show in contract" });

    await user.click(buttons[1]);

    expect(buttons[1].getAttribute("aria-pressed")).toBe("true");
    expect(buttons[0].getAttribute("aria-pressed")).toBe("false");
    const [first, second] = marks();
    expect(second.classList.contains("quote-selected")).toBe(true);
    expect(first.classList.contains("quote-selected")).toBe(false);
    expect(scroll).toHaveBeenCalledTimes(1);
    expect(scroll.mock.contexts[0]).toBe(second);
  });

  it("says plainly when a quote cannot be found, and offers no button for it", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse({
      riskFlags: [
        { clause: "Made up", severity: "MEDIUM", reason: "r", quote: "This sentence is not in the contract." },
        { clause: "Payment", severity: "LOW", reason: "r", quote: PAYMENT_QUOTE },
      ],
    }));

    expect(screen.getByText(/could not be located in the original text/)).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Show in contract" })).toHaveLength(1);
    expect(marks()).toHaveLength(1);
  });

  it("matches a quote that contains redacted personal data, and says the place is approximate", async () => {
    const text = "Send notices to jane.roe@agency.example without delay.";
    await openWith(text, analysisResponse({
      riskFlags: [{ clause: "Notices", severity: "LOW", reason: "r", quote: "Send notices to [REDACTED_EMAIL] without delay." }],
    }));

    expect(marks()).toHaveLength(1);
    expect(marks()[0].textContent).toBe("1" + text);
    expect(screen.getByText(/its place in the text is approximate/)).toBeTruthy();
  });

  it("marks stretches covered by two overlapping quotes as belonging to both", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse({
      riskFlags: [
        { clause: "A", severity: "LOW", reason: "r", quote: "accepts unlimited liability for late" },
        { clause: "B", severity: "HIGH", reason: "r", quote: "liability for late delivery." },
      ],
    }));

    const both = marks().filter((m) => m.getAttribute("data-flags") === "0 1");
    expect(both).toHaveLength(1);
    // This is where quote 2 starts, so it carries the number 2 (quote 1 started on the piece before).
    expect(both[0].textContent).toBe("2liability for late");
    // The overlap takes the stronger severity.
    expect(both[0].classList.contains("severity-high")).toBe(true);
    expect(screen.getAllByLabelText(/^Risk flag [12]$/).map((n) => n.textContent)).toEqual(["1", "2"]);
  });

  it("shows each risk flag with its severity as words and a symbol, and its quote", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse());

    const flags = screen.getAllByRole("listitem").filter((li) => li.classList.contains("flag"));
    expect(flags).toHaveLength(2);
    expect(within(flags[0]).getByText(/High risk/).textContent).toContain("▲");
    expect(within(flags[0]).getByText("Unlimited liability")).toBeTruthy();
    expect(within(flags[0]).getByText(LIABILITY_QUOTE, { selector: "blockquote" })).toBeTruthy();
    expect(within(flags[1]).getByText(/Low risk/).textContent).toContain("▽");
  });

  it("says that the analysis is AI-generated and that a human decides", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse());

    const notice = screen.getByRole("note", { name: "About this analysis" });
    expect(notice.textContent).toMatch(/AI-generated draft/);
    expect(notice.textContent).toMatch(/a human reviewer makes the decision/);
    expect(notice.textContent).toMatch(/analyzer: mock/);
  });
});

describe("contract and analysis text is shown as plain text, never as HTML", () => {
  const hostileText =
    '<img src=x onerror="alert(1)"> and <script>alert(2)</script> <a href="javascript:alert(3)">link</a>\n' +
    LIABILITY_QUOTE;

  it("shows markup in the contract as literal characters and creates no elements from it", async () => {
    await openWith(hostileText, analysisResponse({
      riskFlags: [{ clause: "Liability", severity: "HIGH", reason: "r", quote: LIABILITY_QUOTE }],
    }));

    const region = textRegion();
    expect(region.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(region.textContent).toContain("<script>alert(2)</script>");
    expect(region.querySelector("img")).toBeNull();
    expect(region.querySelector("script")).toBeNull();
    expect(region.querySelector("a")).toBeNull();
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelectorAll("script")).toHaveLength(0);
  });

  it("shows markup in the title, the summary, the reasons and the quotes as literal characters", async () => {
    await openWith(CONTRACT_TEXT, analysisResponse({
      summary: "<b>bold</b> <img src=x onerror=alert(1)>",
      parties: ["<i>Acme</i>"],
      riskFlags: [{ clause: "<u>Clause</u>", severity: "LOW", reason: "<svg onload=alert(1)>", quote: PAYMENT_QUOTE }],
    }), "<h1>Hacked</h1> title");

    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("<h1>Hacked</h1> title");
    expect(screen.getByText("<b>bold</b> <img src=x onerror=alert(1)>")).toBeTruthy();
    expect(screen.getByText("<svg onload=alert(1)>")).toBeTruthy();
    expect(document.querySelector("b")).toBeNull();
    expect(document.querySelector("i")).toBeNull();
    expect(document.querySelector("u")).toBeNull();
    expect(document.querySelector("svg")).toBeNull();
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelectorAll("h1")).toHaveLength(1);
  });

  it("does not run anything when the highlighted text is clicked", async () => {
    const user = await openWith(hostileText, analysisResponse({
      riskFlags: [{ clause: "Liability", severity: "HIGH", reason: "r", quote: LIABILITY_QUOTE }],
    }));
    const alertSpy = vi.fn();
    vi.stubGlobal("alert", alertSpy);

    await user.click(marks()[0]);
    fireEvent.click(textRegion());

    expect(alertSpy).not.toHaveBeenCalled();
  });
});

// Keeps the imports honest.
export type _Unused = ReturnType<typeof userEvent.setup>;
