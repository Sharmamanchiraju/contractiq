# ContractIQ

[![CI](https://github.com/Sharmamanchiraju/contractiq/actions/workflows/ci.yml/badge.svg)](https://github.com/Sharmamanchiraju/contractiq/actions/workflows/ci.yml)

AI-assisted contract intake and review for public-sector procurement teams. Users upload contracts; Claude extracts structured terms and risk flags; a human reviewer approves or rejects; the system warns before contracts expire. The repository currently contains: the Spring Boot backend (login with rate limiting, contracts, human review, asynchronous AI analysis, an audit log), a React web UI, a Python MCP server for AI assistants, and Docker and CI setup. There is no MongoDB or S3 yet.

## Repository layout

- `backend/` – Spring Boot 3, Java 21, Maven (main application)
- `frontend/` – React + Vite + TypeScript
- `mcp-server/` – Python MCP server (read-only access to contract data)
- `tools/` – Python CLI importer and AI evaluation harness
- `deploy/` – Docker Compose, deployment notes (AWS and Azure)
- `docs/` – Architecture, ADRs, `AI_USAGE.md`

## Local infrastructure

1. Copy `.env.example` to `.env` in the repo root and replace the placeholder values.
2. From the repo root, run:

```
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

MySQL is then reachable on `127.0.0.1:3307` (host port 3307, not 3306), which is the default in `DB_URL`.

### Running the backend in a container (optional)

The backend can also run as a container next to the databases. It is behind a compose *profile*, so the command above is unchanged and still starts only MySQL and MongoDB:

```
docker compose --env-file .env -f deploy/docker-compose.yml --profile app up -d --build
```

The image is built from `backend/Dockerfile` (Maven build, then a slim Java 21 runtime, running as a non-root user, with no secrets inside). Its settings come from `.env` when it starts, it uses the mock analyzer unless you opt in to Claude (see the comments in the compose file), and it listens on `127.0.0.1:8080` only. This is for local use; see the deployment design below for how it would run elsewhere.

## Backend (Phase 1)

Requires JDK 21, Maven 3.9+ and Docker (the tests start their own MySQL with Testcontainers).

### Run it

The app reads `JWT_SECRET`, the database settings and the demo-user passwords from environment variables, so `.env` must be loaded into the process. Run this from the repo root. It works from any shell, including fish, because the loading happens inside `bash`:

```
bash -c 'set -a; source .env; set +a; cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev'
```

The `dev` profile turns on the demo-user seeder and Swagger UI (`http://localhost:8080/swagger-ui.html`). Without the `dev` profile, neither is available, and the app refuses to start if `JWT_SECRET` is missing or shorter than 32 bytes.

The seeder creates the organization "Demo Organization" and the users `uploader`, `reviewer` and `admin`. Each password comes from `SEED_UPLOADER_PASSWORD`, `SEED_REVIEWER_PASSWORD` or `SEED_ADMIN_PASSWORD` in `.env`. A user whose variable is empty is not created.

### Try it with curl

```
BASE=http://localhost:8080

# 1. Log in (30-minute token). Same generic 401 for an unknown user and a wrong password.
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"uploader\",\"password\":\"$SEED_UPLOADER_PASSWORD\"}" | jq -r .token)

# 2. Create a contract (status UPLOADED). Organization and creator come from the token.
curl -s -X POST $BASE/api/v1/contracts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Office supplies","vendorName":"Acme Ltd","contractText":"The vendor shall deliver..."}'

# 3. List your organization's contracts (newest first, no contract text).
#    Optional: ?status=ANALYZED&page=0&size=20 (size is capped at 100).
curl -s "$BASE/api/v1/contracts?size=10" -H "Authorization: Bearer $TOKEN"

# 4. Get one contract, including its text. Another organization's contract gives 404.
curl -s $BASE/api/v1/contracts/1 -H "Authorization: Bearer $TOKEN"

# 5. Review (REVIEWER or ADMIN only). Allowed when the status is ANALYZED or NEEDS_MANUAL_REVIEW.
#    A comment is required when REJECTED. The reviewer is taken from the token.
RTOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"reviewer\",\"password\":\"$SEED_REVIEWER_PASSWORD\"}" | jq -r .token)
curl -s -X POST $BASE/api/v1/contracts/1/review -H "Authorization: Bearer $RTOKEN" \
  -H 'Content-Type: application/json' -d '{"decision":"REJECTED","comment":"Unlimited liability clause."}'

# 6. Read the audit log (ADMIN only, your own organization only, newest first, at most 100 per page).
ATOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$SEED_ADMIN_PASSWORD\"}" | jq -r .token)
curl -s "$BASE/api/v1/audit?page=0&size=20" -H "Authorization: Bearer $ATOKEN"
```

The curl examples use `$SEED_UPLOADER_PASSWORD` and `$SEED_REVIEWER_PASSWORD`. Run them in a bash shell after `set -a; source .env; set +a`, or paste your own passwords. A new contract starts as UPLOADED. A review needs the status ANALYZED or NEEDS_MANUAL_REVIEW, which the analysis flow below produces.

Errors use RFC 7807 Problem Details: 400 validation, 401 not logged in, 403 wrong role, 404 not found (or not yours), 409 invalid status move or concurrent change.

**Login rate limit.** After 5 failed logins within 15 minutes, a username (and separately a network address) gets `429 Too Many Requests` with a `Retry-After` header (seconds), even if the next password is right. The answer is the same for usernames that exist and ones that do not. The counters are in memory, so they reset when the backend restarts and are not shared between instances.

**Audit log and request ids.** Logins, contract creation, analysis start and finish, review decisions, refused actions (403) and lookups of contracts outside your organization (404) are recorded in an append-only table with ids only, never passwords, tokens, contract text or comments. `GET /api/v1/audit` (ADMIN, own organization only) reads it. Every response carries an `X-Request-Id` header that also appears in the backend's log lines and in audit entries. Details and limits are in [docs/SECURITY.md](docs/SECURITY.md).

### Run the tests

```
cd backend && mvn -B verify
```

## AI analysis (Phase 2)

An analysis reads a contract and produces a **draft** for a human: parties, dates, renewal and payment terms, obligations, and risk flags that each quote the contract word for word. It can never approve or reject: the result type has no status or decision field, the job can only move a contract to ANALYZED or NEEDS_MANUAL_REVIEW, and only a REVIEWER or ADMIN can call the review endpoint.

Provider: `app.ai.provider` (environment variable `AI_PROVIDER`). The default, `mock`, is a simple rule-based analyzer that needs no network and no API key. Everything below works with it.

### The flow

1. `POST /api/v1/contracts/{id}/analyze` (any role) returns **202** at once. The contract moves to `ANALYZING` in a short transaction. This is allowed from `UPLOADED`, `ANALYZED` and `NEEDS_MANUAL_REVIEW`; from `APPROVED`/`REJECTED` it is 409, and while another analysis is running it is 409.
2. Only after that transaction has committed, the job is queued on a bounded thread pool (`app.ai.executor.*`). If the pool and queue are full, the contract ends as `NEEDS_MANUAL_REVIEW` with code `ANALYSIS_UNAVAILABLE`.
3. The job redacts personal data from the text (`app.ai.redaction.enabled`, on by default), asks the analyzer, and validates every field. Each risk-flag quote must appear word for word in the **redacted** text that was sent (whitespace ignored). If the output is invalid, it retries once with the problems listed. No database transaction is open while the analyzer runs.
4. On success, the analysis is stored as the next version (1, 2, ...) and the contract becomes `ANALYZED`, in one transaction. On failure it becomes `NEEDS_MANUAL_REVIEW` and `analysisErrorCode` holds a safe code: `ANALYSIS_INVALID_OUTPUT`, `ANALYSIS_PROVIDER_ERROR`, `ANALYSIS_TIMEOUT`, `ANALYSIS_TOO_LARGE`, `ANALYSIS_UNAVAILABLE` or `ANALYSIS_INTERNAL_ERROR`. Provider messages are never stored or returned.
5. `GET /api/v1/contracts/{id}/analysis` returns the newest analysis, only for the caller's own organization (404 otherwise, and 404 if there is none yet). Poll the contract's `status`, or this endpoint, to see the outcome.

### Crash recovery

If the app dies mid-analysis, the contract would stay `ANALYZING`. After `app.ai.stale-analysis-timeout` (default 10 minutes) without a change, `analyze` is allowed again and restarts the analysis. An old job that is only slow, not dead, cannot overwrite the newer result: every job remembers the contract version it started with and writes nothing if that has changed.

### Try it (mock provider)

```
BASE=http://localhost:8080
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"uploader\",\"password\":\"$SEED_UPLOADER_PASSWORD\"}" | jq -r .token)

# Upload a contract, then start the analysis (202).
curl -s -X POST $BASE/api/v1/contracts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Supplies","vendorName":"Acme Ltd","contractText":"This agreement is between Acme Ltd and City Council, effective 2026-01-01. The vendor accepts unlimited liability for late delivery."}'
curl -s -i -X POST $BASE/api/v1/contracts/1/analyze -H "Authorization: Bearer $TOKEN"

# A moment later: status is ANALYZED and the analysis is available.
curl -s $BASE/api/v1/contracts/1 -H "Authorization: Bearer $TOKEN" | jq '{status, analysisErrorCode}'
curl -s $BASE/api/v1/contracts/1/analysis -H "Authorization: Bearer $TOKEN" | jq
```

### Using the real Claude API

Switch the provider and give the app your key. The app refuses to start with `AI_PROVIDER=claude` and no `ANTHROPIC_API_KEY`, instead of quietly using the mock. `.env` needs `ANTHROPIC_API_KEY`, and optionally `CLAUDE_MODEL` (default `claude-sonnet-5-5`) and `ANTHROPIC_WORKSPACE_ID`:

```
bash -c 'set -a; source .env; set +a; export AI_PROVIDER=claude; cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev'
```

Then upload and analyze a contract exactly as in the mock example. **This sends the redacted contract text to Anthropic and costs tokens.** Use a non-sensitive test contract first.

How the request is built:
- `POST {base-url}/v1/messages` with headers `x-api-key`, `anthropic-version` (default `2026-10-21`, from `app.ai.claude.anthropic-version`) and, if set, `anthropic-workspace-id`.
- One tool, `record_contract_analysis`, with `strict: true` and `additionalProperties: false` on every object (schema in `backend/src/main/resources/ai/record_contract_analysis.schema.json`), and `tool_choice: auto`. Current models reject a forced tool choice with a 400, so the system prompt tells the model to call the tool exactly once. An answer with no tool call, more than one, or a `stop_reason` other than `tool_use` counts as invalid output (retried once with the problems listed, then `ANALYSIS_INVALID_OUTPUT`).
- No `temperature`, `top_p`, prefill or thinking parameter is sent. The contract goes inside `<contract>` tags and the prompt says it is untrusted data. A closing tag inside the text is neutralized so the contract cannot escape the tags.

Protection around each call (all settings under `app.ai.claude.*`):
- **Timeouts:** 5 s to connect, 60 s to read. **Input guard:** text over 400,000 characters (after redaction) is refused without a call (`ANALYSIS_TOO_LARGE`).
- **Retry** (3 attempts, exponential backoff 1 s, 2 s, ... capped at 10 s, honoring `retry-after`): only HTTP 429, any 5xx including 529, and timeouts. Never other 4xx (400, 401, 402, 403, 404, 413), never a spend-limit 429, and never when `retry-after` is over 30 s. Connection failures (refused, DNS) are not retried.
- **Circuit breaker:** opens after 50% failures over the last 10 calls (at least 5), counting only provider trouble (429, 5xx, timeouts, unreachable). While open, analyses fail at once as `ANALYSIS_UNAVAILABLE` for 30 s.
- **Bulkhead:** at most 4 calls at once; more are refused as `ANALYSIS_UNAVAILABLE`.
- Final failures map to safe codes: timeout is `ANALYSIS_TIMEOUT`; 413 is `ANALYSIS_TOO_LARGE`; 429, 5xx, circuit open, bulkhead full and unreachable are `ANALYSIS_UNAVAILABLE`; other 4xx is `ANALYSIS_PROVIDER_ERROR`.

**Usage log.** Every call attempt writes one row to `ai_usage_log`: contract id, model, input and output tokens, latency in ms, the provider's `request-id`, and an outcome (`OK`, `INVALID_OUTPUT`, `HTTP_429`, `TIMEOUT`, `CIRCUIT_OPEN`, ...). It never holds prompts, contract text, answers or provider messages. Micrometer also records `contractiq.ai.calls` (tagged by outcome) and `contractiq.ai.call.duration`; they are not exposed over HTTP because only `/actuator/health` and `/actuator/info` are.

```
SELECT contract_id, model, input_tokens, output_tokens, latency_ms, request_id, outcome, created_at
FROM ai_usage_log ORDER BY id DESC LIMIT 20;
```

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request, with read-only access to the repository and no secrets:

- **Backend:** `mvn -B verify` on JDK 21, including the Testcontainers integration tests.
- **Frontend:** `npm ci`, type check, tests and production build on Node 20.
- **MCP server:** `pip install` of the pinned requirements and `pytest` on Python 3.12.
- **Docker:** builds the backend image to prove the Dockerfile works (nothing is pushed).

Third-party actions are pinned to full commit SHAs, and dependencies are cached. **Status: the workflow has been linted with `actionlint` and its commands have been run locally, but it has not run on GitHub yet.** The badge at the top shows "no status" until its first run.

## Deploying to AWS or Azure (a design, not a deployment)

**Nothing here has been deployed or tried on AWS or Azure.** It describes how the pieces built so far would run there. There is no deploy step in CI and no Terraform or Bicep yet.

- **Container.** CI would build the backend image from `backend/Dockerfile` and push it to a private registry (Amazon ECR or Azure Container Registry), signing in with short-lived OIDC credentials rather than stored keys. The image holds no keys, passwords or `.env`: everything arrives as environment variables when the container starts.
- **Where it runs.** On AWS, an ECS service on Fargate behind an Application Load Balancer; on Azure, Azure Container Apps with HTTPS ingress. Either one probes `/actuator/health`, terminates TLS in front of the app (the app speaks plain HTTP on 8080), and runs at least one instance. The app calls no cloud API, so its cloud role needs no permissions beyond pulling the image and reading its own secrets.
- **Database.** A managed MySQL 8 (Amazon RDS for MySQL or Azure Database for MySQL Flexible Server) in private subnets with no public access, TLS to the database, and automated backups. Network rules allow only the app to reach port 3306. Liquibase currently runs at startup with the app's own database user, so that user needs schema rights; a stricter design would run migrations as a separate step with a privileged user and give the running app a data-only user.
- **Secrets.** The database password, the JWT secret and, if the real AI is used, the Anthropic key live in AWS Secrets Manager or Azure Key Vault and are injected at start (ECS `secrets` entries; Container Apps secrets backed by Key Vault through a managed identity). They are never in the image, in git, in compose files or in logs.
- **Settings the container needs:** `DB_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`, `JWT_SECRET` (at least 32 bytes), `CORS_ALLOWED_ORIGINS` (the exact web address of the UI), and optionally `AI_PROVIDER`, `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`. The app refuses to start without the required ones.
- **Web UI.** `npm run build` makes static files that could be served from S3 with CloudFront or from Azure Static Web Apps. The API address is fixed at build time (`VITE_API_BASE_URL`) and must match the backend's CORS setting exactly.
- **Logs.** The container writes to standard output, collected by CloudWatch Logs or Log Analytics. The application already avoids logging contract text, tokens and passwords.

**What this design does not solve yet:** analysis jobs and the AI circuit breaker live in each instance's memory (a restart loses queued jobs; the stale-analysis timeout is the only repair); login attempts are not rate limited; there is no audit-log table; and sending contract text to an AI provider needs a data-protection review for public-sector use. The MongoDB and S3 parts are not used by the application yet, so they are not part of this design.

## Security

`docs/SECURITY.md` explains in plain words what is protected and how (sign-in, roles, tenant isolation, redaction, the audit log, login rate limiting, secrets handling) and what is **not** done yet. There is no SOC 2 or ISO 27001 certification; the project only follows that mindset.

## Known limitations

These are deliberate for Phase 1 and should be revisited:

- **JWTs are not checked against the database.** The token is trusted until it expires (30 minutes). A user who is deleted, demoted or has their password changed keeps working access until then. There is no logout, refresh or token revocation.
- **The audit log is basic.** Security-relevant events are recorded (see `docs/SECURITY.md`), but the log is not tamper-evident, has no retention policy, and its append-only protection is in the code only; the matching database grants are a documented recommendation.
- **The login limiter is in memory.** Five failed logins per username or address within 15 minutes gives a 429, but the counts reset when the application restarts and are not shared between instances.
- **Request size is limited by Content-Length.** Bodies over 1 MiB get 413, and chunked uploads (no declared length) get 411. A contract text near the 200,000-character limit with many escaped characters could exceed 1 MiB and be rejected with 413 before validation.
- **Contracts cannot be edited or deleted**, and there is no endpoint to read the review history.
- **Usernames are case-insensitive** (MySQL's default collation), so `Admin` and `admin` are the same user.
- **The real Claude client has not been run against the live API yet.** It is tested against a stubbed server (WireMock) only. In particular, the strict tool schema expresses optional fields as `"type": ["string", "null"]`; the Claude docs did not confirm that form (versus `anyOf`), so the first real call could be rejected with a 400, which would show as `ANALYSIS_PROVIDER_ERROR`. The schema is a plain JSON file, so it is a one-line fix.
- **The mock analyzer is weak.** It finds facts with keyword rules, so its output is far weaker than a model's.
- **The circuit breaker is per application instance.** It trips on a burst of provider failures and then fails analyses fast for 30 seconds; affected contracts end as NEEDS_MANUAL_REVIEW and must be analyzed again.
- **Contract text leaves the system when the provider is `claude`**, after regex redaction (see `Redactor` for its limits). Names and addresses are sent as written.
- **Redaction is regular-expression based.** It does not mask names or addresses, and it can mask harmless long numbers. See the comments on `Redactor` for the exact limits.
- **Analysis jobs live in memory.** A queued job is lost if the app stops; the stale-timeout recovery above is the only repair, and it needs someone to call `analyze` again.
- **CORS** allows only GET, POST and OPTIONS from the origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`).
- **Dev seeder and Swagger** are for local development only. Never run the `dev` profile in production.
