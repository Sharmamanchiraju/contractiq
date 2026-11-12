# ContractIQ backend image: build with Maven, run on a slim JRE 21.
#
# No secrets are built in. The app reads its settings from environment variables at start
# (DB_URL, MYSQL_USER, MYSQL_PASSWORD, JWT_SECRET, and optionally AI_PROVIDER, ANTHROPIC_API_KEY,
# CLAUDE_MODEL, CORS_ALLOWED_ORIGINS) and refuses to start without the required ones.
#
# Build:  docker build -t contractiq-backend backend
# Run:    see deploy/docker-compose.yml (profile "app")

# ---- Stage 1: build the jar -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is reused as long as pom.xml does not change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (mvn verify), not while building the image.
RUN mvn -B -q -DskipTests package

# ---- Stage 2: runtime ------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# A dedicated non-root user: the app never runs as root inside the container.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -h /app app
WORKDIR /app

# Only the jar is copied in: no source, no Maven cache, no .env.
COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# Size the heap from the container's memory limit; exit on out-of-memory so the platform restarts it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The public health endpoint (shows no details). busybox wget is part of Alpine, so nothing extra is installed.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
