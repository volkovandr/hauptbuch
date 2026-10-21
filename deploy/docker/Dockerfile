# Builds the Hauptbuch app image from source — the only thing needed on the host is Docker.
# Used by docker-compose.yml (`docker compose build`); the build context is the repository root.

# ── Stage 1: build the distribution ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# The cache mount keeps Gradle's downloads between builds, so an update does not re-fetch every
# dependency. Tests and quality tools are skipped: `./gradlew check` is the developer's gate.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew installDist --no-daemon

# ── Stage 2: runtime ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

# The in-app database backup shells out to `pg_dump` (ProcessPgDumpRunner), which stock JRE images
# do not ship. It must be at least the server's major version, so the client (17) matches the
# `postgres:17` image in docker-compose.yml — bump both together.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo "$VERSION_CODENAME")-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-17 \
    && apt-get purge -y --auto-remove curl gnupg \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/install/hauptbuch /opt/hauptbuch
EXPOSE 8080
ENTRYPOINT ["/opt/hauptbuch/bin/hauptbuch"]
