# No backup feature — one-click (and eventually automatic) in-cluster database snapshots

Status: needs-triage
Severity: medium
Area: New feature — ops/settings; likely a new module + page

> *Rough capture — filed for a proper grilling later. Nothing here is decided.*

## Request (owner)

A database backup feature, reachable from Settings (or its own page — probably its own page once the
automation below is in scope).

The mechanism the owner has in mind is Postgres' own template copy, in the same cluster:

```sql
CREATE DATABASE hauptbuch_backup_20260823_184144 TEMPLATE hauptbuch;
```

— a full copy of the whole database, kept alongside the live one. On top of that, automation:

- a backup every day;
- and/or a backup every N new transactions (100 was the number named);
- automatic deletion of old backups (some retention policy).

## Why this is worth having

NFR-03 ("documented PostgreSQL backup/restore", **Must**) and FR-PROF-05 (per-profile
backup/restore via `pg_dump`, "Should") are both on the books but unbuilt — the plan parks them
under *Ops & hardening* as "documented backups + export", i.e. a runbook, not a feature. This
request is the in-app version: the owner wants to press a button on the Pi, not remember a shell
incantation.

## Things that will shape the design (flagged, not decided)

- **`CREATE DATABASE … TEMPLATE` requires no other sessions connected to the source database.**
  Postgres refuses with *"source database is being accessed by other users"* while HikariCP holds
  its pool open against `hauptbuch`. So this is not a statement the app can simply issue against its
  own database — it needs a separate connection to another database (`postgres`), and it needs the
  app's own pool quiesced/evicted first (or `pg_terminate_backend` on its own connections, which
  means dropping whatever the user was doing). This is the central constraint of the whole idea and
  the first thing to settle.
- **A copy in the same cluster is not a backup against the failure that matters most.** It survives
  "I deleted the wrong thing" (the likely case, and a good one to cover); it does not survive the
  Pi's SD card or disk dying, which is the failure a single-board home server actually has. Whether
  this feature is *the* backup story or the convenient half of it — with `pg_dump` to somewhere off
  the Pi as the other half — is a scope question, not a technical one.
- **Disk.** A full template copy per day on a Pi grows fast; retention is not a nice-to-have on top,
  it is part of the minimum viable version.
- **Backups contain the Anthropic API key** (it lives in the `settings` row by the NFR-04 exception;
  data-model §3.8 already warns "a `pg_dump` contains the key — guard your backups"). An in-cluster
  copy is no worse than the live DB, but the moment anything leaves the box it matters.
- **Attachments are not in the database.** Receipt images live on disk (ARCH-07,
  `original_path`/`edited_path`). A database-only backup restores a ledger whose receipts point at
  files that may no longer exist. Whether images are in scope, and how the two stay consistent, needs
  an answer.
- **Restore is the hard half.** Creating the snapshot is one statement; using it means either
  pointing the app at the copy (a config change + restart — which is close to how FR-PROF-03 says
  profiles switch) or copying it back over the live database (which has the same
  no-other-sessions problem, from the other direction). "Backup" without a restore path is a false
  sense of safety.
- **Flyway.** A restored older database carries an older schema version; the app applies migrations
  on start, so restoring an old snapshot into a newer app is a forward migration, not a rollback.
  Fine in principle — worth stating explicitly.
- **Where the automation lives.** A daily job is a scheduler (Spring `@Scheduled`, or systemd on the
  Pi doing it outside the app entirely); "every 100 transactions" is a domain trigger inside the app
  and a very different thing. They may not both belong in the same mechanism.
- **Module placement.** Backup is neither `ledger` nor `web`. Probably its own top-level module
  (§1.1) with the page's controller in it; the settings page would link out rather than host it.

## Open questions for the grilling

- Is the in-cluster template copy the whole feature, or the fast path alongside a `pg_dump`-to-file
  (which has no connection restriction, and produces something you can carry off the Pi)?
- Manual button first, automation later — or is the automation the point?
- What does the backup *list* look like, and does restore live in the UI at all or stay a
  documented manual procedure?
- Retention: count-based, age-based, or size-based? What deletes the last one standing (nothing,
  presumably)?
- Does this subsume the NFR-03 "documented backups + export" plan item, or sit next to it?

## Comments

Filed 2026-08-23 from the owner's idea, alongside issues 21 and 22 in `receipt-processing`. First
issue in a new `backup` feature area — nothing exists for this yet.
