import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router";
import { useAuth } from "./AuthContext";

/** Sends visitors who are not signed in to the login page, remembering where they wanted to go. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, signedOutByUser } = useAuth();
  const location = useLocation();
  if (!user) {
    // Remember where a visitor wanted to go (a deep link, or an expired session), but not after an
    // explicit sign-out: the next person to sign in should not land on the previous user's page.
    return <Navigate to="/login" replace state={signedOutByUser ? undefined : { from: location.pathname }} />;
  }
  return <>{children}</>;
}
