import { describe, expect, it } from "vitest";

/**
 * Guards against the two promises that are easy to break by accident later: contract and analysis
 * text is never turned into HTML, and the login token is never written to browser storage.
 * It reads the application's own source files and fails if a forbidden pattern appears.
 */
const sources = import.meta.glob("/src/**/*.{ts,tsx}", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const appFiles = Object.entries(sources).filter(([path]) => !path.includes(".test.") && !path.includes("/src/test/"));

/** Removes comments so a sentence that mentions a forbidden word does not trip the check. */
function code(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");
}

describe("source guards", () => {
  it("found the application's source files to check", () => {
    expect(appFiles.length).toBeGreaterThan(15);
    expect(appFiles.some(([path]) => path.endsWith("ContractText.tsx"))).toBe(true);
  });

  it.each([
    "dangerouslySetInnerHTML",
    "innerHTML",
    "outerHTML",
    "insertAdjacentHTML",
    "document.write",
    "createContextualFragment",
    "eval(",
    "new Function(",
  ])("never uses %s", (forbidden) => {
    const offenders = appFiles.filter(([, source]) => code(source).includes(forbidden)).map(([path]) => path);
    expect(offenders).toEqual([]);
  });

  it.each(["localStorage", "sessionStorage", "document.cookie", "indexedDB"])("never touches %s", (forbidden) => {
    const offenders = appFiles.filter(([, source]) => code(source).includes(forbidden)).map(([path]) => path);
    expect(offenders).toEqual([]);
  });
});
