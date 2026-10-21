# Running Hauptbuch with Docker Compose

The easy way to run Hauptbuch: no Java on the machine that runs it, no PostgreSQL install, no
systemd. Docker starts a PostgreSQL container and the app container together and restarts them
automatically (including after a reboot) until you stop them. For the Raspberry Pi systemd setup,
see [`../as-service/`](../as-service/README.md).

> **Local network only.** There is no login and no HTTPS yet. Do not expose port 8080 to the
> internet.

## What you need

- **Docker** with Compose v2 (`docker compose version`) on the machine that will run the app.
- The repository checked out on that machine.

That is all: Java is only used *inside* the build container. The image is built for the machine
that builds it (build on the Pi for a Pi).

## 1. Configure

```bash
cd deploy/docker
cp .env.example .env
nano .env        # set POSTGRES_PASSWORD
```

Choose the password **before the first start**: PostgreSQL stores it when it first creates the
database, so editing `.env` afterwards does not change it.

## 2. Start

The first start builds the app image from the source (a few minutes, longer on a Pi; later builds
reuse downloaded dependencies).

```bash
# in deploy/docker
docker compose up -d --build
docker compose logs -f app     # Ctrl+C stops watching, not the app
```

Open `http://<host>:8080/` in a browser (`http://localhost:8080/` on the same machine). The database
schema is created automatically on first start.

Both containers are set to `restart: unless-stopped`: they come back after a crash or reboot, and
stay down only if you stopped them yourself with `docker compose stop` / `down`.

## Where your data lives

Everything is kept in plain folders next to `docker-compose.yml`, under `data/`:

| Folder | Contents |
|--------|----------|
| `data/postgres/` | the database itself |
| `data/receipts/` | receipt images |
| `data/statements/` | uploaded bank statements |
| `data/backups/` | database backups taken by the app (nightly at 03:00 and on demand) |

Stopping the stack (`docker compose down`, even `down -v`) **never deletes these folders**, so the
database and receipts are safe while Compose is down. Only deleting `data/` yourself removes them.

On Docker Desktop (macOS, Windows) these folders are owned by your own user and need no special
rights. On Linux the app container runs as root, so the folders are owned by root and you need
`sudo` to browse or copy them.

## Backups

The app takes its own `pg_dump` backups into `data/backups/`: **Settings → Manage backups**, plus
one automatically each night (the last 30 are kept). Three things to know:

1. **Receipt images and statements are not inside a dump** — only the database is. Copy
   `data/receipts/` and `data/statements/` yourself.
2. **Backups sit on the same disk as the database.** Copy `data/` (or at least `data/backups/`)
   somewhere else regularly; the per-backup *Download* button also works.
3. **A dump contains the Anthropic API key**, so treat backup files as secrets.

### Restoring a backup

The app has no restore button. With the stack running:

```bash
# in deploy/docker
docker compose stop app
docker compose exec postgres dropdb -U hauptbuch hauptbuch
docker compose exec postgres createdb -U hauptbuch hauptbuch
docker compose exec -T postgres pg_restore -U hauptbuch -d hauptbuch --no-owner \
  < data/backups/hauptbuch-<yyyyMMdd>-<HHmmss>-<kind>.dump
docker compose start app
```

(On Linux `data/backups/` is owned by root, so prefix the `pg_restore` line with `sudo` if you get
a permission error reading the file — or copy the dump somewhere readable first.)

## Everyday commands

```bash
docker compose ps              # what is running
docker compose logs -f app     # app log
docker compose stop            # stop (data is kept)
docker compose start           # start again
docker compose down            # remove the containers (data is kept)
```

## Updating to a newer version

```bash
# in the project root
git pull
cd deploy/docker
docker compose up -d --build   # rebuilds the image and recreates the app container
```

Database migrations run automatically on start. Take a backup first if you may want to go back —
migrations are one-way.

## Notes

- **Change the port** by setting `HAUPTBUCH_PORT` in `.env`.
- **Phone QR code:** the landing page's "Open on your phone" QR encodes the address your browser
  used, and is hidden when that is `localhost`. To always show a QR that works from a phone, set
  `HAUPTBUCH_PUBLIC_BASE_URL=http://<this-machine-LAN-IP>:8080` in `.env` and run
  `docker compose up -d` (no rebuild needed).
- **Anthropic API key:** enter it in the app's Settings page. `ANTHROPIC_API_KEY` in `.env` works as
  a fallback.
- **PostgreSQL major version** is pinned to 17 in both `docker-compose.yml` and
  `Dockerfile` (`pg_dump` must be at least the server's version). Change them together, and
  note that an existing `data/postgres/` cannot be opened by a different major version without a
  dump/restore.
