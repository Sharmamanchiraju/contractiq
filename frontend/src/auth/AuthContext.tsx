import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { login as apiLogin } from "../api/contracts";
import { ApiError, setToken, setUnauthorizedHandler } from "../api/client";
import type { Role } from "../api/types";
import { roleFromToken } from "./jwt";

export interface AuthUser {
  username: string;
  role: Role;
}

interface AuthContextValue {
  /** The signed-in user, or null. Held in memory only: a page reload signs the user out. */
  user: AuthUser | null;
  /** True after the backend rejected the token, until the next successful sign-in. */
  sessionExpired: boolean;
  /** True after the user chose "Sign out", until the next sign-in. The next person starts at the list. */
  signedOutByUser: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [signedOutByUser, setSignedOutByUser] = useState(false);

  // When any API call gets a 401, the client has already dropped the token; drop the user too, so
  // the protected pages redirect to the login page.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
      setSessionExpired(true);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    const response = await apiLogin(username, password);
    const role = roleFromToken(response.token);
    if (!role) {
      throw new ApiError("The server sent a sign-in answer this page could not understand.", 200);
    }
    setToken(response.token);
    setSessionExpired(false);
    setSignedOutByUser(false);
    setUser({ username, role });
  }, []);

  const signOut = useCallback(() => {
    setToken(null);
    setUser(null);
    setSessionExpired(false);
    setSignedOutByUser(true);
  }, []);

  const value = useMemo(
    () => ({ user, sessionExpired, signedOutByUser, signIn, signOut }),
    [user, sessionExpired, signedOutByUser, signIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside an AuthProvider");
  return value;
}

/** What each role may do in the UI. The backend enforces the same rules; this only drives the buttons. */
export function can(role: Role | undefined, action: "upload" | "analyze" | "review"): boolean {
  if (!role) return false;
  if (role === "ADMIN") return true;
  if (action === "review") return role === "REVIEWER";
  return role === "UPLOADER";
}
