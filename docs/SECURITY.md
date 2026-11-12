# Security, in plain words

ContractIQ handles contracts, which are sensitive. This page says what is protected and how, and, just as important, **what is not done yet**.

**Status, honestly:** the project is built with a SOC 2 / ISO 27001 *mindset* (least privilege, an audit trail, no secrets in code, tests for the security rules). It is **not certified, not audited by anyone outside, and not penetration tested.** Do not read this page as a compliance claim.

## What is protected, and how

### Who you are (sign-in)
- Passwords are stored only as BCrypt hashes. The plain password is never stored or logged.
- A successful login returns a **JWT** (a signed token) valid for 30 minutes. It carries the user id, the organization id and the role. It is signed with a secret taken from the environment (at least 32 bytes). The application **refuses to start** without one (outside the dev profile).
- A wrong password and an unknown username give the **same answer** (401, "Invalid username or password"). For an unknown username the server still checks the password against a dummy hash, so the response time does not give the username away.
- **Rate limiting** (see below) slows password guessing.

### What you may do (roles)
- Three roles: UPLOADER (upload and start analyses), REVIEWER (approve or reject), ADMIN (everything, including the audit log).
- The server checks the role on every request. The web UI hides buttons you cannot use, but that is only a convenience: the real check is on the server. The UI reads the role from the token for display only.
- **The AI can never approve or reject a contract.** There is no code path for it: the analysis result has no decision field, the analysis job can only move a contract to "analyzed" or "needs manual review", and only the review endpoint, which needs a REVIEWER or ADMIN, can approve or reject.

### Whose data it is (tenant isolation)
- Every user and every contract belongs to one organization. The organization id comes from the token, never from the request body.
- Every database lookup of contracts, analyses and audit entries is filtered by the caller's organization. Another organization's contract is reported as **404 Not Found**, exactly like one that does not exist, so its existence is not revealed.
- This is covered by tests for reading, analyzing, reviewing and the audit log.

### What leaves the system, and what the AI sees
- By default the AI is a built-in rule-based mock that needs no key and sends nothing anywhere.
- With the real Claude API turned on, contract text is sent to Anthropic **after redaction**: emails, phone numbers, national-ID-like numbers and bank-account-like numbers are replaced with placeholders. Redaction is pattern matching with real limits (it does not mask names or addresses, and can mask harmless long numbers), described in the code comments of `Redactor`. It reduces what is shared; it does not make sharing safe by itself.
- The model's answer must pass a strict schema and every field is validated. Each risk flag must quote the contract word for word, and the quote is checked against the text that was sent.
- Contract text is treated as **untrusted data, never instructions**: it is wrapped in tags with a system prompt saying so, the answer must come through a strict tool schema, and text that looks like instructions is reported as a risk flag. The web UI shows contract and analysis text as plain text (no HTML), and the MCP server labels it untrusted.
- Contract text, passwords, tokens and API keys are never written to logs.

### The audit log
- A table, `audit_log`, records security-relevant events: **login success and failure, a login lock-out, contract created, analysis started and finished, review approved or rejected, access denied (403), and a contract looked up that is not in the caller's organization (404).**
- Each entry holds: when, organization, user, action, what kind of thing and which one, the outcome, the id of the request, and the network address. Nothing else. There is **no column for a password, a token, contract text, a title, or a review comment**, and a test scans the table after real flows to check none of them ever appears. A failed login for a username that does not exist does not store the name that was typed (people sometimes type a password there).
- **State changes are audited in the same database transaction as the change.** There is never a change without its entry, and if the entry cannot be written the change is rolled back. Logins and refusals are written separately on a best-effort basis: if the audit table is broken, people can still sign in and a 403 stays a 403 (only the exception type is logged).
- **Append-only in the code.** The application has no update or delete path for the table: the repository has only "add" and "read" methods, the entity has no setters, and tests fail if someone adds a way to change or remove entries. See "Making the audit log append-only in the database" below for the part the code cannot do.
- Administrators read it with `GET /api/v1/audit?page=0&size=20` (newest first, at most 100 per page). **Only an ADMIN can, and only for their own organization.** There is no way to change or delete an entry over HTTP.
- Every request gets a server-made **request id**, returned in the `X-Request-Id` header and shown in log lines, so an audit entry can be matched with the application log.
- Not recorded on purpose: 404s for "no analysis yet" (an everyday event) and for unknown URLs (scanner noise), so the log is not flooded.

### Login rate limiting
- After **5 failed logins within 15 minutes**, a username (and, separately, a network address) is refused with **429 Too Many Requests** and a `Retry-After` header, until the oldest failure is 15 minutes old. Both numbers are settings (`app.security.login.*`).
- A locked username is refused **even with the right password**, so guessing cannot succeed while locked. The refusal is the same for real and made-up usernames, so it does not reveal which exist. While locked, no password is checked, so refusals are cheap.
- A successful login clears that username's count.
- The web UI shows "Too many sign-in attempts, try again later."

### Headers, input and errors
- Every API answer (including 401, 403, 404, 413 and 429) carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: ... frame-ancestors 'none'`, `Referrer-Policy: no-referrer` and `Cache-Control: no-store` (so a token or contract is not kept by a browser or proxy). Spring Security supplies most of these by default; the rest are set in `SecurityConfig`. A test checks them on each kind of answer. `Strict-Transport-Security` is set but only takes effect over HTTPS.
- Cross-origin browser access is allowed only from the exact origin in `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`), with no wildcard.
- Request bodies over 1 MiB are refused; fields are validated (contract text at most 200,000 characters).
- Errors are returned in a standard format and never contain a stack trace or internal message. An unexpected error returns a generic 500 with an error id, and only the exception type goes to the log.
- Database access uses parameterized queries only.

### Secrets
- Secrets (database password, JWT secret, the Anthropic key, demo-user passwords) come from environment variables. `.env` is ignored by git; `.env.example` holds only fake values.
- The Docker image contains no secrets: they are supplied when the container starts. The container does not even receive the Anthropic key unless you opt in to the real AI.
- Anything in the environment can be seen by someone with access to the running container or the process list, so in a real deployment the values should come from a secrets manager (see the deployment design in the main README), not from a file on disk.
- The demo users (`uploader`, `reviewer`, `admin`) exist only in the dev profile.

### How it is built and run
- The container runs as a non-root user, with a read-only filesystem, no Linux capabilities and no privilege escalation (in the compose profile).
- CI has read-only repository access and no secrets, third-party actions are pinned to exact commits, and dependencies are pinned to exact versions.
- The MCP server (for AI assistants) has no tool that approves or rejects, uses an UPLOADER-level account, and logs no tokens, passwords or contract text. The web UI keeps the token in memory only.

## Making the audit log append-only in the database (recommended for production)

The code has no way to update or delete an audit entry, but a database user with full rights could still do it with hand-written SQL. In this project one database user does everything, including creating tables, so the database cannot enforce this by itself. (Database triggers do not help: on a normal MySQL setup an ordinary user is not allowed to create them.)

For production, use two database users: one that runs the migrations (and is used only by the deployment step), and one for the running application that has **no UPDATE or DELETE on `audit_log`**. For example, as a database administrator, after the migrations have run:

```sql
-- The running application's user (name and password are placeholders):
CREATE USER 'contractiq_app'@'%' IDENTIFIED BY '<from the secrets manager>';

-- Normal tables: everything the application needs to read and change.
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.organizations     TO 'contractiq_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.app_users         TO 'contractiq_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.contracts         TO 'contractiq_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.reviews           TO 'contractiq_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.contract_analyses TO 'contractiq_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON contractiq.ai_usage_log      TO 'contractiq_app'@'%';

-- The audit log: add and read only. No UPDATE, no DELETE, no DROP.
GRANT SELECT, INSERT ON contractiq.audit_log TO 'contractiq_app'@'%';
```

Grant on each table (as above) rather than on the whole database, because MySQL cannot subtract a table-level right from a database-level grant unless partial revokes are switched on. The migration user (which needs schema rights) must not be the one the running application uses. **This split is not built or tested in this project yet**; the SQL is the design. It also needs the Liquibase migrations to run with the migration user, which the deployment design in the main README mentions as a future step.

## What is NOT done yet

- **No refresh tokens and no revocation.** A token is valid until it expires (30 minutes). There is no server-side sign-out.
- **The token is not checked against the database.** A user who is deleted, demoted, or whose password changes keeps their access (and their old role) until the token expires, at most 30 minutes.
- **The login limiter is in memory.** It resets when the application restarts and is not shared between instances, so with several instances an attacker gets several times the allowance. Locking by username also lets someone lock a real user out for 15 minutes by failing logins as them. Behind a proxy or load balancer the address it sees is the proxy's, so everyone would share one allowance until forwarded-address headers are trusted deliberately (they are not by default). There are no rate limits on other endpoints.
- **The audit log is not tamper-evident.** Entries are not chained or signed, they are not copied to separate write-once storage, and there is no retention or archiving policy. The database-level protection above is a recommendation, not something applied. Failed logins for usernames that do not exist have no organization, so no administrator can see them through the API (they are in the database only). Reading the audit log is not itself audited. The network address in an entry is personal data under many privacy laws and needs a retention decision.
- **One database user does everything** (migrations and normal use) today.
- **No multi-factor authentication, no password rules, no password change or reset, no user management.** Accounts are created by hand or by the dev seeder.
- **No certification and no outside review.** There is no SOC 2 or ISO 27001 certification and no independent audit or penetration test. What exists is a way of working and tests.
- **No automated vulnerability scanning** of dependencies or container images in CI, and no software bill of materials.
- **Contracts are stored as plain text in the database.** Protection at rest depends on the database's own encryption and on backups being protected. There is no application-level encryption, and backup and recovery are not designed.
- **Sending contract text to an AI provider** has not been assessed for data-protection or procurement rules. The redaction has the limits described above.
- **TLS is not handled by the application.** It speaks plain HTTP and expects HTTPS to be provided in front of it.
- **The read-only AI assistant account can still write.** ContractIQ has no read-only role, so the MCP server's UPLOADER account can create contracts and start analyses through the API, even though the MCP server exposes no way to create one.
