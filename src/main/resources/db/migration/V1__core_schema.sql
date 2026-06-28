-- V1 — the core double-entry schema (data-model §3).
--
-- This is the transaction engine's foundation: currencies, the single-row settings entity that
-- holds the write-once base currency, accounts (which also back categories and per-person debts),
-- transactions and their signed postings, the sparse carry-forward exchange-rate cache, and payees.
--
-- Conventions (data-model §3.0): every entity has a surrogate PK `<entity>_id`; a FK reuses the
-- target's PK name. Two exceptions: externally-defined entities keep their natural key
-- (`currency.currency_code`), and self-references take a role name (`parent_id`). Junction tables
-- get their own surrogate PK with the natural pair as a `unique` constraint.
--
-- Monetary columns are `numeric`, never floating point (tech-stack §2.4). Integrity invariants
-- (sum-to-zero, leaves-only) are upheld in the application layer and verified by the SQL-logic
-- suite, not by DB triggers (data-model §8, T-DM-2).

-- ── currency (externally-defined — natural key) ──────────────────────────────
create table currency (
  currency_code text primary key,   -- ISO 4217, e.g. 'EUR', 'CHF'
  minor_units   smallint not null,  -- 2 for EUR, 0 for JPY — drives rounding
  symbol        text,
  name          text not null
);

-- ── settings (single-row root entity, data-model §3.8) ───────────────────────
-- Holds the write-once base currency (required before any transaction, immutable thereafter) and
-- the display name. The `settings_id = 1` check plus the default make this a strictly single-row
-- table — global book settings, not a key/value bag.
create table settings (
  settings_id   smallint primary key default 1 check (settings_id = 1),
  base_currency text references currency(currency_code),  -- write-once; NULL until first-run set
  display_name  text
);

-- The single row exists from the start with a NULL base currency; first-run setup fills it in.
insert into settings (settings_id, base_currency, display_name) values (1, null, null);

-- ── payee ────────────────────────────────────────────────────────────────────
create table payee (
  payee_id   bigint generated always as identity primary key,
  name       text not null,
  deleted_at timestamptz
);

-- ── account (real accounts, categories, and per-person debts — data-model §3.2) ──
create table account (
  account_id    bigint generated always as identity primary key,
  name          text not null,
  type          text not null
                check (type in ('asset', 'liability', 'income', 'expense', 'equity')),
  parent_id     bigint references account(account_id),     -- self-ref; NULL = top level
  currency_code text not null references currency(currency_code),
  opened_at     date,
  closed_at     date,
  deleted_at    timestamptz                                -- soft delete
);

-- "What references this account as a parent?" and balance rollups both walk parent_id.
create index account_parent_id_idx on account (parent_id);

-- ── transaction (an economic event — carries NO amount, data-model §3.5) ─────
create table transaction (
  transaction_id bigint generated always as identity primary key,
  date           date not null,                       -- booking date
  payee_id       bigint references payee(payee_id),   -- nullable (transfers have none)
  note           text,
  lifecycle      text not null default 'confirmed'
                 check (lifecycle in ('pending_review', 'confirmed')),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz                          -- orthogonal to lifecycle
);

-- ── posting (one signed leg hitting exactly one leaf account, data-model §3.6) ──
-- Sign convention: + = debit, − = credit. The legs of a transaction sum to zero (in native for
-- single-currency, in base for cross-currency). `base_amount` is NULL except on the handful of
-- genuinely two-currency legs, where it is a frozen historical fact and must never be recomputed.
create table posting (
  posting_id     bigint generated always as identity primary key,
  transaction_id bigint not null references transaction(transaction_id),
  account_id     bigint not null references account(account_id),   -- always a LEAF
  amount         numeric(19, 4) not null,   -- signed; native currency of the account
  base_amount    numeric(19, 4),            -- NULL = derive on the fly; non-null = frozen fact
  reconciliation text not null default 'unreconciled'
                 check (reconciliation in ('unreconciled', 'cleared', 'reconciled')),
  note           text
);

-- Running balances and rollups scan postings by account; the join from a transaction to its legs
-- is the hot path.
create index posting_account_id_idx on posting (account_id);
create index posting_transaction_id_idx on posting (transaction_id);

-- ── exchange_rate (sparse, carry-forward lookup cache, data-model §3.7) ──────
-- Units of BASE per 1 unit of currency_code. A lookup cache only: it proposes rates on entry and
-- revalues held balances for net worth. It NEVER rewrites a booked conversion (those are frozen on
-- the posting's base_amount).
create table exchange_rate (
  exchange_rate_id bigint generated always as identity primary key,
  currency_code    text not null references currency(currency_code),  -- the foreign currency
  date             date not null,
  rate             numeric(19, 8) not null,   -- units of BASE per 1 unit of currency_code
  source           text not null check (source in ('ecb', 'manual')),
  unique (currency_code, date)
);
