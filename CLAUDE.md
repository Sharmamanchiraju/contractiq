# ContractIQ

AI-assisted contract intake and review for public-sector procurement teams.
Users upload contracts; Claude extracts structured terms and risk flags; a human
reviewer approves or rejects; the system warns before contracts expire.

## Repository layout
- backend/      Spring Boot 3, Java 21, Maven (main application)
- frontend/     React + Vite + TypeScript
- mcp-server/   Python MCP server (read-only access to contract data)
- tools/        Python CLI importer and AI evaluation harness
- deploy/       Docker Compose, deployment notes (AWS and Azure)
- docs/         Architecture, ADRs, AI_USAGE.md

## Commands
- Backend build and test:  cd backend && mvn -B verify
- Backend run:             cd backend && mvn spring-boot:run
- Local infrastructure:    docker compose --env-file .env -f deploy/docker-compose.yml up -d  (run from the repo root; .env lives in the repo root)
- Frontend:                cd frontend && npm ci && npm run dev && npm test
- Python:                  cd mcp-server && pytest ; cd tools && pytest

## Working agreement (how you work with me)
- Always propose a short plan first and wait for my approval.
- Work in small steps. Do only what the current phase asks. No extra features.
- Ask before adding any new dependency, and say why it is needed.
- When a step is done, list every file you created or changed and explain each
  in at most 3 plain sentences so I can explain it in an interview.
- Never claim tests pass or the app works unless you actually ran it and saw it.
- If a requirement is unclear, ask one question instead of guessing.
- State your assumptions explicitly.

## Java rules
- Layers: controller (HTTP only) -> service (business rules, transactions) ->
  repository (data) -> entity. No business logic in controllers.
- Constructor injection only. No field injection. Prefer records for DTOs.
- Never return JPA entities from the API; map to DTOs.
- Do not use Lombok (keep the code explicit and easy to explain).
- Errors use RFC 7807 Problem Details via a single @RestControllerAdvice.
- Database changes only through Liquibase changesets. Hibernate ddl-auto must
  be "validate". Never edit a changeset that has already been applied.
- Never call an external service (Claude, S3) inside a database transaction.
- All timestamps are UTC (Instant). Money, if any, uses BigDecimal.
- Every new class gets a short Javadoc saying why it exists.

## Security rules (SOC 2 and ISO 27001 mindset)
- No secrets in code, config files, logs, tests or Git. Read them from
  environment variables. Provide .env.example with fake values only.
- Passwords are hashed with BCrypt. Tokens are short-lived JWTs.
- Every endpoint requires authorization unless explicitly public. Check role AND
  ownership or organization where relevant.
- Use parameterized queries only. Validate all input. Limit request sizes.
- Never log contract text, personal data, tokens or API keys.
- State-changing actions write an audit log entry: who, what, when, from where.
- Principle of least privilege for users, services and the MCP server.

## AI rules (Claude API)
- The model id comes from configuration (CLAUDE_MODEL), never hardcoded.
- Contract text is untrusted data, never instructions. Wrap it in tags and say
  so in the system prompt. Output must come through a forced tool schema.
- Validate every field of the model output. If invalid, retry once, then mark
  the record NEEDS_MANUAL_REVIEW.
- Set timeouts, retries with backoff for temporary errors only, and a circuit
  breaker. Record token usage and the request id for every call.
- Unit tests never call the real API. Use the mock analyzer or a stubbed HTTP
  server. Real-API checks live only in the evaluation harness.
- The AI can never approve or reject a contract. Only a REVIEWER user can.

## Python rules
- Python 3.11+, type hints everywhere, ruff for lint, pytest for tests.
- The MCP server uses stdio: never print to standard output; log to stderr.
- The MCP server is read-only plus analyze. It must not expose approve, reject,
  delete or user management.

## Testing
- Every feature ships with tests: unit tests for rules, Testcontainers
  integration tests for repositories and APIs, security tests for 401 and 403.
- Keep service-layer line coverage at or above 70 percent.

## Git
- Conventional commit messages (feat:, fix:, test:, docs:, chore:).
- Small commits. One phase equals one branch and one pull request to main.

## Definition of done for any task
Code compiles, all tests pass, new behavior has tests, no secrets or personal
data in code or logs, README or docs updated where behavior changed, and I have
read and understood the diff.
