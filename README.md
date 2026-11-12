# ContractIQ

AI-assisted contract intake and review for public-sector procurement teams. Users upload contracts; Claude extracts structured terms and risk flags; a human reviewer approves or rejects; the system warns before contracts expire. This repository is currently at Phase 1: the Spring Boot backend core (login, contracts, human review). There is no AI analysis, MongoDB, S3 or UI yet.

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

## Backend (Phase 1)

Requires JDK 21, Maven 3.9+ and Docker (the tests start their own MySQL with Testcontainers).

### Run it

Load `.env` into your shell first, so the app can read `JWT_SECRET`, the database settings and the demo-user passwords. Run this from the repo root, in the same shell you start the app from:

```
set -a; source .env; set +a
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
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
```

`SEED_*` must be exported in your shell for the curl examples (`set -a; source .env; set +a` does this). A new contract starts as UPLOADED. Nothing moves it to ANALYZED until the AI analysis exists (a later phase), so for now a review returns 409 unless you set the status yourself, as the tests do.

Errors use RFC 7807 Problem Details: 400 validation, 401 not logged in, 403 wrong role, 404 not found (or not yours), 409 invalid status move or concurrent change.

### Run the tests

```
cd backend && mvn -B verify
```

## Known limitations

These are deliberate for Phase 1 and should be revisited:

- **JWTs are not checked against the database.** The token is trusted until it expires (30 minutes). A user who is deleted, demoted or has their password changed keeps working access until then. There is no logout, refresh or token revocation.
- **No audit log.** CLAUDE.md asks for an audit entry on every state-changing action (who, what, when, from where). Phase 1 does not store one yet; only a general application log exists, and it never contains contract text, passwords or tokens.
- **No login rate limiting or account lockout.** Repeated password guesses are not slowed down, other than by BCrypt's own cost.
- **Request size is limited by Content-Length.** Bodies over 1 MiB get 413, and chunked uploads (no declared length) get 411. A contract text near the 200,000-character limit with many escaped characters could exceed 1 MiB and be rejected with 413 before validation.
- **Contracts cannot be edited or deleted**, and there is no endpoint to read the review history.
- **Usernames are case-insensitive** (MySQL's default collation), so `Admin` and `admin` are the same user.
- **Status moves to ANALYZING/ANALYZED/NEEDS_MANUAL_REVIEW exist in the rules but nothing triggers them yet.** They arrive with the AI analysis phase.
- **CORS** allows only GET, POST and OPTIONS from the origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`).
- **Dev seeder and Swagger** are for local development only. Never run the `dev` profile in production.
