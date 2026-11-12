import { describe, expect, it } from "vitest";
import { fakeToken } from "../test/helpers";
import { roleFromToken } from "./jwt";

describe("roleFromToken", () => {
  it.each(["UPLOADER", "REVIEWER", "ADMIN"])("reads the %s role", (role) => {
    expect(roleFromToken(fakeToken({ sub: "7", org: 1, role }))).toBe(role);
  });

  it("returns null for an unknown role", () => {
    expect(roleFromToken(fakeToken({ role: "SUPERUSER" }))).toBeNull();
    expect(roleFromToken(fakeToken({ role: 5 }))).toBeNull();
    expect(roleFromToken(fakeToken({}))).toBeNull();
  });

  it.each(["", "not-a-token", "a.b", "a.%%%.c", "..", "x.e30.y"])("returns null for malformed input %j", (token) => {
    expect(roleFromToken(token)).toBeNull();
  });

  it("handles base64url characters", () => {
    // A payload long enough that standard base64 would contain '+' or '/' somewhere is hard to
    // craft by hand, so check the conversion with a role plus padding-sensitive length.
    expect(roleFromToken(fakeToken({ role: "REVIEWER", pad: "??>>" }))).toBe("REVIEWER");
  });
});
