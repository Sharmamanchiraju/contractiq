# ContractIQ

AI-assisted contract intake and review for public-sector procurement teams. Users upload contracts; Claude extracts structured terms and risk flags; a human reviewer approves or rejects; the system warns before contracts expire. This repository is currently at Phase 0: skeleton only, with no application code yet.

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
