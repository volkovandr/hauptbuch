-- V19 — the import staging schema (import.md §11).
--
-- The whole QIF/Money migration runs here: nothing reaches `transaction`/`posting` until the
-- commit (import.md §2, §10), so a campaign that goes wrong is discarded, not unwound. The full
-- seven-table schema lands now and later slices fill it in progressively (the V9 receipt
-- precedent): b1 writes only `import_session`.
--
-- Naming per CLAUDE.md §5. The `transaction_id` FK follows the `receipt.transaction_id` precedent
-- (null until booked). Every child of a session cascades on delete so a discarded campaign is one
-- statement to purge.

-- The single open campaign: state, the campaign-wide charset/date-order defaults the first file
-- sets and later files inherit (b2), and the commit timestamp.
create table import_session (
  import_session_id  bigint generated always as identity primary key,
  state              text not null default 'open'
                     check (state in ('open', 'committed', 'discarded')),
  default_charset    text check (default_charset in ('utf_8', 'windows_1252')),
  default_date_order text check (default_date_order in ('day_month', 'month_day', 'ambiguous')),
  started_at         timestamptz not null default now(),
  committed_at       timestamptz
);

-- At most one open session (the DB backstop to ImportSessionService's guard — the `settings_id = 1`
-- stance: an invariant worth a schema constraint, not only application code).
create unique index import_session_single_open_idx on import_session (state) where state = 'open';

-- One uploaded export. The file does not say which account it is for; the owner states it
-- (`money_account_name`, §4.1). `charset`/`date_order` are what it was actually decoded and parsed
-- with, after the owner confirmed or overrode the detection (§4.3, §4.4).
create table import_file (
  import_file_id        bigint generated always as identity primary key,
  import_session_id     bigint not null references import_session(import_session_id) on delete cascade,
  filename              text not null,       -- reference only; carries no identity (§2)
  money_account_name    text not null,
  charset               text not null check (charset in ('utf_8', 'windows_1252')),
  date_order            text not null check (date_order in ('day_month', 'month_day', 'ambiguous')),
  proposed_account_type text check (proposed_account_type in ('asset', 'liability')),
  transaction_count     integer not null default 0,
  staged_at             timestamptz not null default now()
);

create index import_file_session_idx on import_file(import_session_id);

-- The account map (§5.1, §5.4), accumulated across files. Each Money account name maps to exactly
-- one of an existing Hauptbuch account or a person — never both. `target_currency_code` is the
-- currency the owner picks for a new account or a person's leaf (QIF carries none, §5.4); null when
-- the target is an existing account. `expect_file` tracks "still waiting for this account's own
-- export?" — the commit gate's only escape hatch (§6.4). The opening-balance columns record the c3
-- reconciliation against the target account's existing opening balance (§5.1).
create table import_account (
  import_account_id      bigint generated always as identity primary key,
  import_session_id      bigint not null references import_session(import_session_id) on delete cascade,
  money_account_name     text not null,
  account_id             bigint references account(account_id),
  person_id              bigint references person(person_id),
  target_currency_code   text references currency(currency_code),
  expect_file            boolean not null default true,
  opening_balance_choice text
                         check (opening_balance_choice in ('keep_hauptbuch', 'take_money', 'override')),
  opening_balance_amount numeric(19, 4),
  unique (import_session_id, money_account_name),
  check (account_id is null or person_id is null)
);

-- The category map (§5.2), keyed by the full Money path (`Audi:Fuel`), which Money keeps unique.
-- The target is a semantic category node plus zero or more tags (junction below) — never a
-- currency leaf; `CurrencyLeafService` routes to that at commit. The debit/credit counters are the
-- sign evidence the review shows (§9).
create table import_category (
  import_category_id bigint generated always as identity primary key,
  import_session_id  bigint not null references import_session(import_session_id) on delete cascade,
  money_path         text not null,
  account_id         bigint references account(account_id),
  debit_line_count   integer not null default 0,
  credit_line_count  integer not null default 0,
  proposed_type      text check (proposed_type in ('income', 'expense')),
  unique (import_session_id, money_path)
);

-- Junction: a mapped path's tags (§5.2, §8).
create table import_category_tag (
  import_category_tag_id bigint generated always as identity primary key,
  import_category_id     bigint not null references import_category(import_category_id) on delete cascade,
  tag_id                 bigint not null references tag(tag_id),
  unique (import_category_id, tag_id)
);

-- A staged transaction. Carries no amount (data-model §3.5) — that lives in the postings. The `C`
-- field lands as `cleared_status` and is applied to every leg at commit (§4.2). `payee_destroyed`
-- marks a `P` field that was entirely `?`/whitespace on export (§4.4) — distinct from an absent
-- payee, since the review reports its count (§5.3). `opening_balance` marks Money's
-- opening-balance self-transfer (§5.1). `state` tracks the row through mirror matching and parking
-- (§6): `ready` books at commit, `mirrored`/`excluded` do not, `parked` blocks the gate.
create table import_transaction (
  import_transaction_id bigint generated always as identity primary key,
  import_file_id        bigint not null references import_file(import_file_id) on delete cascade,
  date                  date not null,
  payee_text            text,
  payee_destroyed       boolean not null default false,
  note                  text,
  reference_number      text,                -- the `N` field; prefixed into the note at commit (§4.2)
  cleared_status        text not null default 'unreconciled'
                        check (cleared_status in ('unreconciled', 'cleared', 'reconciled')),
  opening_balance       boolean not null default false,
  state                 text not null default 'ready'
                        check (state in ('ready', 'parked', 'mirrored', 'excluded')),
  transaction_id        bigint references transaction(transaction_id)
);

create index import_transaction_file_idx on import_transaction(import_file_id);

-- A staged leg. Its target is the still-unresolved Money string: a category path (resolved via
-- `import_category`) xor an account name (resolved via `import_account`); the funding leg names the
-- file's own account. `class_name` is the `/Class` suffix, a tag at commit (§8). `mirror_pair_id`
-- links the two sightings of a transfer once matched within staging — a per-posting-pair link,
-- because a split leg's mirror can arrive as an unsplit transaction (§6.1).
create table import_posting (
  import_posting_id     bigint generated always as identity primary key,
  import_transaction_id bigint not null references import_transaction(import_transaction_id) on delete cascade,
  amount                numeric(19, 4) not null,   -- signed, native to the mapped account's currency
  note                  text,                      -- the split `E` memo
  money_category_path   text,
  money_account_name    text,
  class_name            text,
  mirror_pair_id        bigint references import_posting(import_posting_id),
  check (money_category_path is null or money_account_name is null)
);

create index import_posting_transaction_idx on import_posting(import_transaction_id);
create index import_posting_mirror_pair_idx on import_posting(mirror_pair_id);
