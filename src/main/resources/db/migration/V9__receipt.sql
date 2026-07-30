-- V9 — receipt (stage 9b: receipts walking skeleton; data-model §13.1)
--
-- A captured scan moving through a stored lifecycle toward at most one transaction (1:0..1 both
-- ways). The full ratified table lands now; later slices consume its columns progressively — 9b
-- only writes `state`, `captured_at`, `source`, `original_path` and clears the rest.
--
-- `state` and `deleted_at` are orthogonal (data-model §13.1, mirroring lifecycle⊥deleted_at on
-- transaction): `discarded` = "looked and chose not to book" (kept for the record); `deleted_at` =
-- "remove this row" (reversible soft-delete). The original image is immutable (ARCH-07); the edited
-- image (9c) and the raw parse (9e) are derived working copies.
create table receipt (
  receipt_id     bigint generated always as identity primary key,
  state          text not null default 'new'
                 check (state in ('new','pre_processed','processing',
                                  'processed','committed','discarded','failed')),
  captured_at    timestamptz not null default now(),
  source         text not null check (source in ('mobile','pc','telegram')),
  original_path  text not null,           -- raw scan on the Pi; NEVER mutated (ARCH-07)
  edited_path    text,                    -- derived, post-preprocess image actually sent to the AI
  ai_note        text,                    -- per-receipt prompt guidance (receipt doc §8)
  batch_id       text,                    -- Batches API id while processing (NULL for single mode)
  parse_raw      text,                    -- raw AI response, retained immutable (audit)

  -- denormalised parsed header (register list / filter / search; blank until processed):
  merchant_text  text,
  receipt_date   date,
  total_amount   numeric(19,4),
  currency_code  text references currency(currency_code),
  account_id     bigint references account(account_id),   -- detected/picked paying account
  transaction_id bigint references transaction(transaction_id),  -- NULL until committed
  deleted_at     timestamptz                              -- orthogonal soft-delete (§13.1)
);

-- The register and mobile grid both sort by capture time within a live-rows window; the state
-- filter narrows it. A single index on the common (deleted_at is null, captured_at) access path.
create index receipt_live_captured_idx on receipt (captured_at) where deleted_at is null;
