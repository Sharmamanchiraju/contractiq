# deploy

Docker Compose and deployment notes (AWS and Azure). Currently holds `docker-compose.yml`, which runs MySQL 8 and MongoDB 7 bound to `127.0.0.1` only. MySQL is published on host port **3307** (container port 3306); MongoDB uses 27017. Deployment notes will be added in later phases.

## Usage

1. Copy `.env.example` to `.env` in the repo root and replace the placeholder values.
2. From the repo root, run:

```
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

Stop with `docker compose --env-file .env -f deploy/docker-compose.yml down` (data persists in named volumes `mysql_data` and `mongo_data`).
