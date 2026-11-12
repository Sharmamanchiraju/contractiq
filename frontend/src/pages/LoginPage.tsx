import { useId, useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ErrorMessage } from "../components/ErrorMessage";

/** Only follow a "return to" path that stays inside this app. */
function safeDestination(from: unknown): string {
  return typeof from === "string" && from.startsWith("/") && !from.startsWith("//") && from !== "/login"
    ? from
    : "/contracts";
}

export function LoginPage() {
  const { user, sessionExpired, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const usernameId = useId();
  const passwordId = useId();
  const destination = safeDestination((location.state as { from?: unknown } | null)?.from);

  if (user) return <Navigate to={destination} replace />;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
      navigate(destination, { replace: true });
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
      setPassword("");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login" id="main">
      <h1>ContractIQ</h1>
      <p className="muted">Sign in to review contracts.</p>
      {sessionExpired ? (
        <div className="notice" role="status">
          Your session has expired. Please sign in again.
        </div>
      ) : null}
      <form onSubmit={onSubmit} aria-label="Sign in">
        <label htmlFor={usernameId}>Username</label>
        <input
          id={usernameId}
          name="username"
          autoComplete="username"
          required
          maxLength={64}
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
        <label htmlFor={passwordId}>Password</label>
        <input
          id={passwordId}
          name="password"
          type="password"
          autoComplete="current-password"
          required
          maxLength={128}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <button type="submit" className="button button-primary" disabled={submitting}>
          {submitting ? "Signing in…" : "Sign in"}
        </button>
      </form>
      {error ? <ErrorMessage message={error} /> : null}
    </main>
  );
}
