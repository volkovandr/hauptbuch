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

# Accessing from the outside

On any machine of the same LAN where the Pi is, open the browser and go to `http://<pi-host>:8080/`. You should see the app's login page. 
