# deploy

Docker Compose and deployment notes. `docker-compose.yml` runs MySQL 8 and MongoDB 7 bound to `127.0.0.1` only, and can optionally run the backend too. MySQL is published on host port **3307** (container port 3306); MongoDB uses 27017. The AWS and Azure deployment design is in the root README (a design only: nothing is deployed).

## Usage

1. Copy `.env.example` to `.env` in the repo root and replace the placeholder values.
2. From the repo root, run:

```
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

Stop with `docker compose --env-file .env -f deploy/docker-compose.yml down` (data persists in named volumes `mysql_data` and `mongo_data`).

## Also running the backend (optional profile)

The `backend` service only starts when you ask for the `app` profile, so the commands above behave exactly as before:

```
docker compose --env-file .env -f deploy/docker-compose.yml --profile app up -d --build
```

- It builds `backend/Dockerfile` and listens on `127.0.0.1:8080`.
- Inside the compose network it reaches MySQL as `mysql:3306` (the `3307` mapping is only for tools on your own machine).
- It needs `JWT_SECRET`, `MYSQL_USER`, `MYSQL_PASSWORD` and `MYSQL_DATABASE` in `.env`; compose stops with a clear message if one is missing.
- It uses the mock analyzer by default. For the real Claude API, set `AI_PROVIDER=claude` and add the two `ANTHROPIC_API_KEY` / `CLAUDE_MODEL` lines shown in the compose file; the key is not passed to the container otherwise.
- It runs as a non-root user with a read-only filesystem, no Linux capabilities and no privilege escalation.
- Stop only the backend with `docker compose --env-file .env -f deploy/docker-compose.yml --profile app rm -sf backend`.
