# Second deployment recipe — Docker Compose for non-technical users

Status: resolved
Category: enhancement
Area: Deployment (`deploy/`), build (`build.gradle`)

## Why

The only recipe today (systemd on a Raspberry Pi) needs a Java runtime, native PostgreSQL, a system
user and hand-edited config. The goal of this second recipe: run the app **without systemd and
without installing Java or PostgreSQL** on the host — Docker only (the image is built inside
Docker too).

## Decisions (owner, 2026-10-21)

- Existing recipe moves to `deploy/as-service/`; the new one lives in `deploy/docker/` with its own
  README next to the `docker-compose.yml`.
- Compose runs a PostgreSQL container and the app container (configured via environment to use it),
  `restart: unless-stopped`.
- **All state is bind-mounted into a `data/` folder next to the compose file** — `postgres/`,
  `receipts/`, `statements/`, `backups/` — so the database and receipts survive `docker compose
  down` (even `down -v`) and are visible/copyable on the host filesystem.
- The **user builds the image** for now (no registry/CI publishing), on the machine that runs it, so
  the **architecture is the builder's**.
- **Build inside Docker, so Docker is the only host requirement.** A first iteration used the Jib
  Gradle plugin (needs a local JDK plus a hand-built base image); replaced the same day by a
  multi-stage `deploy/docker/Dockerfile` (JDK stage runs `installDist`, runtime stage is Temurin
  JRE + `pg_dump`), built via `build:` in the compose file. Jib was removed from `build.gradle`
  because its config depended on the dropped base-image step. Running Gradle+Jib in a container was
  rejected (Jib needs the Docker CLI/daemon inside it).
- **Blocker resolved — `pg_dump`.** The in-app backup feature shells out to `pg_dump`
  (`ProcessPgDumpRunner`); stock JRE images lack it. Chosen: a tiny custom base image
  (the runtime stage of `deploy/docker/Dockerfile`: Temurin 25 JRE + `postgresql-client-17`). Rejected: `docker exec` into the DB container (needs the Docker socket), and a separate
  backup sidecar (duplicates the in-app feature and its UI).
