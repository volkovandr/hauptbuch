# Deploying to the Raspberry Pi

Packaging decision and rationale: `docs/tech-stack.md` §7 (ARCH-01, revised 2026-08-17). No
Docker — `./gradlew installDist` produces a plain (non-fat) distribution; systemd runs its
generated start script directly. PostgreSQL is native on the Pi already (ARCH-02) — nothing here
touches it beyond creating the app's database and role.

Scope as decided: **local LAN only for now.** No login (ARCH-04) and no HTTPS reverse proxy
(ARCH-05) exist yet — both are still backlog. Do not port-forward or otherwise expose this beyond
the home network until one of those lands.

**Placeholders used below** — substitute your own values, they are not defaults:

- `<pi-host>` — the Pi's hostname or IP (e.g. `raspberrypi.local`).
- `<pi-user>` — your login account on the Pi. Recent Raspberry Pi OS images no longer create a
  `pi` user by default; use whatever account you set up during imaging.

**Where each command runs** is called out on its comment line — either `# on the dev machine, from
the project root` or `# on the Pi`. Nothing here assumes the full repo is checked out on the Pi;
only the built distribution and the two `deploy/` files get copied over.

## Prerequisites on the Pi

**Java.** The distribution's start script needs a `java` on `PATH` — Raspberry Pi OS does not ship
one. `./gradlew installDist` builds against Java 25 (`build.gradle`'s toolchain), so the Pi needs a
Java 25+ runtime. Eclipse Temurin (Adoptium) publish a JRE for aarch64:

```bash
# on the Pi — check you're on 64-bit Raspberry Pi OS first; Temurin doesn't publish JDK/JRE 25
# for 32-bit ARM. `uname -m` should print aarch64. If it prints armv7l, re-image with the 64-bit
# Raspberry Pi OS build (Pi 4/5 both support it) rather than chasing a 32-bit JRE.
uname -m

sudo apt update && sudo apt install -y wget gpg apt-transport-https
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
$(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-25-jre   # JRE is enough — nothing compiles on the Pi

java -version   # sanity check
```

A JDK (`temurin-25-jdk`) also works if you'd rather have one JVM install for everything; the JRE is
just smaller and this deployment never compiles anything on the Pi.

**PostgreSQL** How to install PostgreSQL is out of scope of this document. However, this worked for me:

```bash
sudo apt-get install postgresql postgresql-contrib
```

## One-time Pi setup

Stage the two `deploy/` files onto the Pi first, then do the rest over SSH.

```bash
# on the dev machine, from the project root
scp deploy/hauptbuch.service deploy/application.yml.example <pi-user>@<pi-host>:/tmp/
```

```bash
# on the Pi
ssh <pi-user>@<pi-host>

# 1. System user for the service (no login shell, no home dir).
sudo useradd --system --no-create-home --shell /usr/sbin/nologin hauptbuch

# 2. Data dir for receipt images (hauptbuch.receipts.storage-root).
sudo mkdir -p /var/lib/hauptbuch/receipts
sudo chown -R hauptbuch:hauptbuch /var/lib/hauptbuch

# 3. Config dir + file. A fresh Pi has no $EDITOR set — nano ships with Raspberry Pi OS by
#    default; swap in vim/whatever you actually have if you prefer.
sudo mkdir -p /etc/hauptbuch
sudo cp /tmp/application.yml.example /etc/hauptbuch/application.yml
sudo nano /etc/hauptbuch/application.yml   # fill in the real DB password, etc.
sudo chown root:hauptbuch /etc/hauptbuch/application.yml
sudo chmod 640 /etc/hauptbuch/application.yml

# 4. App install dir.
sudo mkdir -p /opt/hauptbuch
sudo chown hauptbuch:hauptbuch /opt/hauptbuch

# 5. Database + role (adjust to however the existing native Postgres is administered).
sudo -u postgres createuser hauptbuch --pwprompt
sudo -u postgres createdb hauptbuch --owner=hauptbuch

# 6. Install the systemd unit itself (not started yet — see "Build and ship" below first).
sudo cp /tmp/hauptbuch.service /etc/systemd/system/hauptbuch.service
sudo systemctl daemon-reload
```

## Build and ship (from the dev machine)

```bash
# on the dev machine, from the project root
./gradlew installDist

# Stage to a temp dir on the Pi, then move into place with the right ownership — avoids needing
# passwordless sudo over rsync's own remote shell.
rsync -a --delete build/install/hauptbuch/ <pi-user>@<pi-host>:/tmp/hauptbuch-dist/
ssh <pi-user>@<pi-host> '
  sudo rsync -a --delete /tmp/hauptbuch-dist/ /opt/hauptbuch/ &&
  sudo chown -R hauptbuch:hauptbuch /opt/hauptbuch
'
```

## Start the service (first time only)

```bash
# on the Pi
sudo systemctl enable --now hauptbuch
```

Flyway applies all migrations automatically on first start (CLAUDE.md §2). Check it came up:

```bash
# on the Pi
sudo systemctl status hauptbuch
journalctl -u hauptbuch -f
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/
```

## Redeploys

Repeat the "Build and ship" step above, then:

```bash
ssh <pi-user>@<pi-host> sudo systemctl restart hauptbuch
```

`/etc/hauptbuch/application.yml` and `/var/lib/hauptbuch/` are untouched by a redeploy — only
`/opt/hauptbuch/` (the code) is replaced.

**When a release adds config, merge it in.** Because your `application.yml` is never overwritten, a
redeploy that introduces new settings leaves them at the packaged defaults, which are tuned for dev
rather than the Pi. The backup settings are the current example: without a `hauptbuch.backup` block
the root defaults to `./.local-backups`, resolved against `WorkingDirectory=/opt/hauptbuch` — which
`ProtectSystem=strict` makes read-only, since the unit only grants write access to
`/var/lib/hauptbuch`. Taking a backup then fails with a read-only-filesystem error naming that
path. Diff your file against `deploy/application.yml.example` after a redeploy and copy across
anything new.

## Backup and restore

The app takes its own database backups: a `pg_dump` custom-format file per backup, written to
`/var/lib/hauptbuch/backups` (inside the data dir the unit already lists in `ReadWritePaths`, so no
unit change is needed). Configured under `hauptbuch.backup` in `/etc/hauptbuch/application.yml` —
see `deploy/application.yml.example`.

- **By hand:** Settings → Manage backups → *Take a backup now*.
- **Automatically:** one per night at `daily-cron` (03:00 by default), plus a catch-up at startup if
  the Pi was off when it was due.
- **Retention:** automatic backups are kept to `keep-automatic` (30 by default); the oldest beyond
  that are deleted after each scheduled run. **Manual backups are never swept** — delete them
  yourself from the listing. A sweep never empties the directory.

`pg_dump` must be on `PATH` — it comes with the `postgresql` packages already installed above, and
must be at least the server's major version.

### Three things these backups do not do

1. **Receipt images are not included.** The dump is the database only. A restored ledger can
   reference images that are gone, and viewing such a receipt will error. Copy the image tree
   separately:

   ```bash
   # on the dev machine — the receipts tree is not in any dump
   rsync -av <pi-user>@<pi-host>:/var/lib/hauptbuch/receipts/ ./receipts-backup/
   ```

2. **They are on the same SD card as the database.** They survive deleting the wrong thing; they do
   not survive the card dying. Use the per-backup *Download* button to keep a copy elsewhere.

3. **A dump contains the Anthropic API key.** The key lives in the `settings` row (NFR-04,
   data-model §3.8), so every dump carries it. The backup directory is created owner-only; treat a
   downloaded dump as a secret.

### Restoring

There is deliberately **no restore button**. A restore has to drop and recreate the live database,
which the app cannot do to the connection it is holding open, and the unit denies it the privilege
to stop and start itself. The backup screen shows these commands per backup with the filename
already filled in — copy them from there rather than retyping:

```bash
# on the Pi
sudo systemctl stop hauptbuch
sudo -u postgres dropdb hauptbuch
sudo -u postgres createdb hauptbuch --owner=hauptbuch
sudo -u postgres pg_restore --dbname=hauptbuch --no-owner \
  /var/lib/hauptbuch/backups/hauptbuch-<yyyyMMdd>-<HHmmss>-<kind>.dump
sudo systemctl start hauptbuch
```

Restoring an **older** dump into a **newer** app is a forward migration, not a rollback: Flyway
applies any migrations added since, on the next start. That is fine, and it is one-way — take a
fresh backup first if you might want to come back.

## Accessing from the outside

On any machine on the same LAN as the Pi, open a browser at `http://<pi-host>:8080/`. You should
see the landing page — there is no login (ARCH-04 is still backlog).

To get the app onto a phone, use the **Open on your phone** panel at the bottom of that landing
page rather than typing the URL: it shows a QR of the app's own address, and scanning it lands the
phone straight on the receipt-capture screen (a phone is redirected there from `/`).

The URL in that panel is read off the request you are making, so it is already right for a direct
`http://<pi-host>:8080/` deployment, with nothing to configure.

**Behind a gateway or reverse proxy**, make the proxy pass these headers and the panel follows the
address your browser actually used — path prefix included:

- `X-Forwarded-Proto`
- `X-Forwarded-Host`
- `X-Forwarded-Prefix` — needed if the app is served under a path, e.g. `/pi/hauptbuch`

The app trusts them because it is LAN-only and single-user: no redirect or auth surface consumes
these values, so the worst a spoofed header can do is put a wrong QR on your own landing page.

If the proxy cannot be made to send them, set `hauptbuch.public-base-url` in
`/etc/hauptbuch/application.yml` to the URL you want encoded (see `application.yml.example`); it
wins over whatever the request says. The panel is hidden entirely when the app is reached over
loopback, which is why it does not appear in local development.
