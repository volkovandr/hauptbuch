# Deploying to the Pi

Packaging decision and rationale: `docs/tech-stack.md` §7 (ARCH-01, revised 2026-08-17). No
Docker — `./gradlew installDist` produces a plain (non-fat) distribution; systemd runs its
generated start script directly. PostgreSQL is native on the Pi already (ARCH-02) — nothing here
touches it beyond creating the app's database and role.

Scope as decided: **local LAN only for now.** No login (ARCH-04) and no HTTPS reverse proxy
(ARCH-05) exist yet — both are still backlog. Do not port-forward or otherwise expose this beyond
the home network until one of those lands.

## One-time Pi setup

```bash
# 1. System user for the service (no login shell, no home dir).
sudo useradd --system --no-create-home --shell /usr/sbin/nologin hauptbuch

# 2. Data dir for receipt images (hauptbuch.receipts.storage-root).
sudo mkdir -p /var/lib/hauptbuch/receipts
sudo chown -R hauptbuch:hauptbuch /var/lib/hauptbuch

# 3. Config dir + file. Copy deploy/application.yml.example, fill in the real DB password
#    (and optionally ANTHROPIC_API_KEY), then lock it down — it holds secrets.
sudo mkdir -p /etc/hauptbuch
sudo cp application.yml.example /etc/hauptbuch/application.yml
sudo $EDITOR /etc/hauptbuch/application.yml
sudo chown root:hauptbuch /etc/hauptbuch/application.yml
sudo chmod 640 /etc/hauptbuch/application.yml

# 4. App install dir.
sudo mkdir -p /opt/hauptbuch
sudo chown hauptbuch:hauptbuch /opt/hauptbuch

# 5. Database + role (adjust to however the existing native Postgres is administered).
sudo -u postgres createuser hauptbuch --pwprompt
sudo -u postgres createdb hauptbuch --owner=hauptbuch
```

## Build and ship (from a dev machine)

```bash
./gradlew installDist

# Stage to a temp dir on the Pi, then move into place with the right ownership — avoids needing
# passwordless sudo over rsync's own remote shell.
rsync -a --delete build/install/hauptbuch/ pi@raspberrypi.local:/tmp/hauptbuch-dist/
ssh pi@raspberrypi.local '
  sudo rsync -a --delete /tmp/hauptbuch-dist/ /opt/hauptbuch/ &&
  sudo chown -R hauptbuch:hauptbuch /opt/hauptbuch
'
```

## Install and start the service (first time only)

```bash
ssh pi@raspberrypi.local
sudo cp deploy/hauptbuch.service /etc/systemd/system/hauptbuch.service
sudo systemctl daemon-reload
sudo systemctl enable --now hauptbuch
```

Flyway applies all migrations automatically on first start (CLAUDE.md §2). Check it came up:

```bash
sudo systemctl status hauptbuch
journalctl -u hauptbuch -f
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/
```

## Redeploys

Repeat the "Build and ship" rsync step, then:

```bash
ssh pi@raspberrypi.local sudo systemctl restart hauptbuch
```

`/etc/hauptbuch/application.yml` and `/var/lib/hauptbuch/` are untouched by a redeploy — only
`/opt/hauptbuch/` (the code) is replaced.
