-- V24 — the commit-time ledger duplicate scan (import.md §9; plan f1).
--
-- The import campaign runs for weeks while Hauptbuch stays in daily use, so by commit time some
-- staged transactions may already have been entered into the ledger by hand. Before the f2 commit,
-- every staged transaction that will book is compared against the *live* ledger on date + funding
-- account + amount + category, and every hit is presented for the owner to adjudicate — never a
-- silent auto-skip (import.md §9). This is the fourth commit-gate condition; the other three are
-- e4's (ImportIssues).
--
-- Q-IMP-5, settled here (import.md §9, §14): the scan is a re-runnable snapshot with its own
-- timestamp, re-run from a button — there is NO ledger lock. A decision the owner made against a
-- ledger transaction that has since changed is re-raised as `pending` on the next re-run rather
-- than trusted (`ledger_seen_at` below records what was adjudicated against).

-- One current snapshot per campaign. Re-running updates `ran_at` in place and diffs the matches
-- (plan f1). No row here ⇒ the scan has never run ⇒ the commit gate is locked.
create table import_duplicate_scan (
  import_duplicate_scan_id bigint generated always as identity primary key,
  import_session_id        bigint not null references import_session(import_session_id) on delete cascade,
  ran_at                   timestamptz not null default now(),
  unique (import_session_id)
);

-- One staged-transaction ↔ live-transaction overlap the scan found. A staged transaction can match
-- more than one ledger transaction (and vice versa) — one row per pair, each adjudicated on its
-- own. `adjudication` starts `pending` (blocks the gate); `import` books the staged row anyway at
-- f2, `skip` drops it. `ledger_seen_at` is `transaction.updated_at` captured when the owner
-- adjudicated — the next re-run compares it against the current value and re-raises the decision to
-- `pending` if the ledger transaction has moved on. Null while `pending`.
create table import_duplicate_match (
  import_duplicate_match_id bigint generated always as identity primary key,
  import_duplicate_scan_id  bigint not null references import_duplicate_scan(import_duplicate_scan_id) on delete cascade,
  import_transaction_id     bigint not null references import_transaction(import_transaction_id) on delete cascade,
  transaction_id            bigint not null references transaction(transaction_id),
  adjudication              text not null default 'pending'
                            check (adjudication in ('pending', 'import', 'skip')),
  ledger_seen_at            timestamptz,
  unique (import_duplicate_scan_id, import_transaction_id, transaction_id)
);

create index import_duplicate_match_scan_idx on import_duplicate_match(import_duplicate_scan_id);
