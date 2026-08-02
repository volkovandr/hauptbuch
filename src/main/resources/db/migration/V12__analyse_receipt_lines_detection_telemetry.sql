-- Stage 9e — Analyse (single): the schema the analyse worker seeds into, the account-detection
-- config it reads, and the settings AI section it is configured from (data-model §13.1/§13.2/§13.4,
-- §3.8). Slice-local, landing with the sub-stage that consumes it (the stage-7 precedent).

-- ── The editable working copy seeded from the raw parse (data-model §13.2) ──────────────────────
create table receipt_line (
  receipt_line_id bigint generated always as identity primary key,
  receipt_id      bigint not null references receipt(receipt_id),
  description     text,
  amount          numeric(19, 4) not null,                  -- native currency of the paying account
  account_id      bigint references account(account_id),    -- category leaf OR a real transfer target
  person_id       bigint references person(person_id),      -- set ⇒ beneficiary (person-debt) leg
  note            text,
  sort_order      int
);

create index receipt_line_receipt_idx on receipt_line (receipt_id);

-- Tags on a draft line — mirrors posting_tag one-for-one (data-model §13.2).
create table receipt_line_tag (
  receipt_line_tag_id bigint generated always as identity primary key,
  receipt_line_id     bigint not null references receipt_line(receipt_line_id),
  tag_id              bigint not null references tag(tag_id),
  unique (receipt_line_id, tag_id)
);

-- ── Parse telemetry + denormalised header on the receipt (data-model §13.1) ─────────────────────
-- Written once by the analyse worker; blank until processed.
alter table receipt add column parse_error        text;             -- why the parse failed; NULL on success
alter table receipt add column tokens_in           int;             -- the API usage block, per parse
alter table receipt add column tokens_out          int;
alter table receipt add column tokens_cache_write  int;
alter table receipt add column tokens_cache_read   int;
alter table receipt add column parse_cost          numeric(12, 6);  -- USD, computed at analyse time and FROZEN

alter table receipt add column merchant_city    text;
alter table receipt add column merchant_country text;
alter table receipt add column receipt_time     time;               -- as printed; no zone
alter table receipt add column receipt_number   text;               -- printed Beleg-Nr.

-- ── Paying-account detection config, on the account (data-model §13.4) ───────────────────────────
alter table account add column card_last4   text;                            -- card slips: '1234'
alter table account add column cash_account boolean not null default false;  -- matches 'Bar'/cash

-- ── The settings AI section (data-model §3.8) ───────────────────────────────────────────────────
-- The one DB-stored secret is ai_api_key (NFR-04 amended, owner decision 2026-08-01): write-only
-- masked in the UI, never logged, ANTHROPIC_API_KEY env as fallback.
alter table settings add column ai_model             text;           -- Anthropic model id; default claude-sonnet-5
alter table settings add column ai_api_key           text;           -- the one DB-stored secret; NULL = env only
alter table settings add column ai_price_in          numeric(12, 6); -- USD per million input tokens
alter table settings add column ai_price_out         numeric(12, 6); -- per million output tokens
alter table settings add column ai_price_cache_write numeric(12, 6); -- per million cache-write tokens
alter table settings add column ai_price_cache_read  numeric(12, 6); -- per million cache-read tokens
