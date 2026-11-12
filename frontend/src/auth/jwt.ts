import { ROLES, type Role } from "../api/types";

/**
 * Reads the role out of a JWT so the UI can show or hide buttons.
 *
 * This does NOT verify the token (the browser has no secret to verify it with, and must not).
 * The role is for display only: the backend checks every request itself, so editing the role in a
 * token only changes which buttons appear, never what the backend allows.
 */
export function roleFromToken(token: string): Role | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const role = (JSON.parse(json) as { role?: unknown }).role;
    return typeof role === "string" && (ROLES as readonly string[]).includes(role) ? (role as Role) : null;
  } catch {
    return null;
  }
}
