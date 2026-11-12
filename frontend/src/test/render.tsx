import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { App } from "../App";
import { AuthProvider } from "../auth/AuthContext";

type Entry = string | { pathname: string; state?: unknown };

/** Renders the whole app (routes, auth) at the given path, as a browser at that URL would. */
export function renderApp(entry: Entry) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** Fills in and submits the login form shown on screen. */
export async function signIn(user = userEvent.setup(), username = "tester", password = "not-a-real-password") {
  await user.type(await screen.findByLabelText("Username"), username);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  return user;
}
