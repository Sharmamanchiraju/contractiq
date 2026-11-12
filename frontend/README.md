# ContractIQ web UI

A React single-page app for ContractIQ. People sign in, upload contracts, start the AI analysis, read the analysis next to the original text, and (reviewers) approve or reject. It only talks to the ContractIQ REST API; it has no server of its own.

Built with Vite, React 19, TypeScript and React Router, plain CSS, and Vitest with Testing Library. Every dependency version is pinned exactly.

## Run it

You need **Node 20.19 or newer** and a running ContractIQ backend (see the root README: `bash -c 'set -a; source .env; set +a; cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev'`).

```
cd frontend
npm ci
npm run dev
```

Then open **http://localhost:5173** (not `127.0.0.1`). The backend only allows that exact address (its CORS setting, `CORS_ALLOWED_ORIGINS`, defaults to `http://localhost:5173`), and a browser blocks everything from any other address. The dev server uses `strictPort`, so if 5173 is busy it stops with an error instead of moving to a port the backend would refuse.

The backend address defaults to `http://localhost:8080`. To change it, copy `.env.example` to `.env` and set `VITE_API_BASE_URL`.

| Command | What it does |
|---|---|
| `npm run dev` | Starts the dev server on port 5173 |
| `npm test` | Runs all tests once (Vitest, jsdom) |
| `npm run typecheck` | Checks the TypeScript without building |
| `npm run build` | Typechecks and builds into `dist/` |

For a quick look, sign in with the dev demo users (`uploader`, `reviewer`, `admin`) that the backend's dev profile creates, using the passwords in your `.env` (`SEED_*_PASSWORD`).

## What each role sees

| | UPLOADER | REVIEWER | ADMIN |
|---|---|---|---|
| Read contracts and analyses | yes | yes | yes |
| Upload a contract | yes | no | yes |
| Analyze / Analyze again | yes | no | yes |
| Approve or Reject (comment required) | no | yes | yes |

This only drives which buttons appear. The backend checks every request itself.

## The screens

- **Sign in**: username and password. A wrong password says so; an expired session says that instead.
- **Contracts**: filter by status, status shown as words plus a symbol, paging, and loading, empty and error states.
- **Upload**: title, vendor and text, with the backend's limits (300, 300 and 200,000 characters).
- **Contract**: the original text on the left, the AI analysis on the right (summary, parties, dates, terms, obligations, risk flags with severity). Each risk flag shows its quote; "Show in contract" scrolls to it and highlights it in the text. A notice states that the analysis is an AI-generated draft and that a human decides.
- **Analyze**: starts the analysis, then checks the status every 2 seconds until it leaves ANALYZING. If it fails, the error code is explained in plain words and the contract is marked as needing a person.
- **Decision**: reviewers write a comment (required for both Approve and Reject) and decide. The buttons stay off, with the reason shown, until the contract can be decided and a comment is written.

## Safety and accessibility

- The login token is kept **in memory only**: never in `localStorage`, `sessionStorage`, cookies or the URL.
- A 401 from the backend clears the token and returns to the sign-in page.
- All contract and analysis text is rendered as plain text. There is no `dangerouslySetInnerHTML` or `innerHTML` anywhere; a test scans the source and fails if one appears. A contract containing `<script>` or `<img onerror=...>` is shown as literal characters.
- Fields have labels, errors are announced (`role="alert"`), polling status is announced (`aria-live`), focus is always visible, there is a "skip to content" link, and status and severity are shown as words plus a symbol, never color alone. Highlighted quotes carry the flag's number as well as a color.

## Known limitations

- **The token is only in memory, so reloading the page signs you out.** That is deliberate (nothing sensitive is stored in the browser), but it also means no "stay signed in". Tokens last 30 minutes and there is no refresh: after that you sign in again.
- **There is no review history.** The backend has no endpoint that returns past reviews. After you decide, the page shows your decision and comment from the response; after a reload it can only show that the contract is Approved or Rejected, not who decided or why.
- **The role is read from the JWT for display only.** The UI decodes the token payload (without verifying it, which a browser cannot do) to decide which buttons to show. Someone who edits the token only changes which buttons appear: the backend still enforces every permission.
- **Quote highlighting is best-effort.** It ignores differences in line breaks and spacing and highlights the first occurrence only. The backend checks quotes against the text sent to the AI, with personal data masked (for example `[REDACTED_EMAIL]`). The UI treats a placeholder as "any short text on one line", so a quote containing one is found approximately, and says so. A quote that cannot be found is reported as such rather than guessed.
- **Polling has limits.** It stops after 5 failed checks in a row or after 5 minutes, and then offers "Check again". An analysis that is still running keeps running on the server.
- **The newest `react-router`, `vitest` and `jsdom` need Node 22.** To work on Node 20 these use the newest versions that support it (react-router 7, vitest 4, jsdom 27, Vite 7).
- **Only the exact origin `http://localhost:5173` works** with the default backend settings. A browser cannot tell a CORS block from a dead server, so both show "Could not reach the server".

## The files

```
index.html               The page the app mounts into, plus an empty favicon so browsers do not log a 404.
package.json             Dependencies (exact versions) and the npm commands. package-lock.json pins the full tree.
vite.config.ts           Vite and Vitest settings: React plugin, port 5173 (strict), jsdom for tests.
tsconfig.json            Strict TypeScript settings.
.env.example             The one setting you may change: VITE_API_BASE_URL.

src/main.tsx             Starts the app inside the router and the auth provider.
src/App.tsx              The route table: sign-in, list, upload, detail; everything else needs a login.
src/styles.css           All styling: light and dark themes, badges, the two-column layout, focus outlines.

src/api/types.ts         TypeScript shapes of the backend's JSON (contracts, analysis, review, roles).
src/api/client.ts        The only code that calls fetch. Keeps the token in memory, adds timeouts, turns
                         errors into plain messages, and reports a 401 so the app can sign out.
src/api/contracts.ts     One small function per backend endpoint.

src/auth/AuthContext.tsx Who is signed in, sign-in and sign-out, the "session expired" and "signed out by
                         the user" flags, and the role-to-button rules (can()).
src/auth/jwt.ts          Reads the role out of the token for display, without verifying it.
src/auth/RequireAuth.tsx Sends visitors who are not signed in to the login page and remembers where they
                         wanted to go (except after an explicit sign-out).

src/lib/highlight.ts     Finds a quote in the contract text (tolerant of spacing and redaction
                         placeholders) and splits the text into highlighted pieces.
src/lib/format.ts        Plain-language wording: status and severity labels and symbols, error codes.

src/hooks/useContractPolling.ts
                         The 2-second status polling and every rule for when it stops.

src/components/Layout.tsx        The header, navigation, user badge and sign-out, around signed-in pages.
src/components/StatusBadge.tsx   A contract status as text, symbol and color.
src/components/SeverityBadge.tsx A risk severity as text, symbol and color.
src/components/AiNotice.tsx      The "AI-generated draft, a human decides" banner.
src/components/ContractText.tsx  The original text with highlighted, numbered quotes (plain text only).
src/components/AnalysisPanel.tsx The analysis: summary, key facts, obligations and risk flags.
src/components/ReviewPanel.tsx   The reviewer's comment box and Approve and Reject buttons.
src/components/ErrorMessage.tsx  A readable error with an optional "Try again" button.

src/pages/LoginPage.tsx          The sign-in form.
src/pages/ContractListPage.tsx   The contract list with filter and paging.
src/pages/UploadPage.tsx         The upload form.
src/pages/ContractDetailPage.tsx The contract screen: loads data, starts the analysis, polls, shows the
                                 result or the failure, and hosts the review panel.

src/test/                Test support: setup, a fake backend, fixtures, and render helpers.
src/**/*.test.ts(x)      The tests (see below).
```

## Tests

`npm test` runs 131 tests. Highlights:

- **Login flow** (`LoginPage.test.tsx`): success, wrong password, unreachable server, return to the page you wanted, sign-out, nothing stored in the browser.
- **Role-based buttons** (`roles.test.tsx`): the table above for each role and status, the required comment, upload and list pages.
- **Quote highlighting** (`highlight.test.ts`, `highlighting.test.tsx`): exact and messy-whitespace matches, redaction placeholders, overlaps, quotes that cannot be found, and HTML in contract text shown as text.
- **Polling stop** (`polling.test.tsx`): exact 2-second timing with fake timers, and stopping on success, failure, unmount, 401, repeated errors and the 5-minute cap.
- **401 handling** (`unauthorized.test.tsx`, `client.test.ts`): every screen and action returns to sign-in, and a 401 from the login itself is a wrong password.
- **Source guards** (`security.test.ts`): no `innerHTML`-style APIs and no browser storage anywhere in the app code.
