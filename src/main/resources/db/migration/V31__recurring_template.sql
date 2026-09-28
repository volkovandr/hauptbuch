-- V31 — recurring templates (data-model §14, recurring sub-plan slice a; ADR 0002).
--
-- A template is a stored dock entry plus a schedule. It stores the ENTRY, not postings: every booking
-- resolves it through the dock's operations path, so leaf routing, provisioning, the sign model and
-- the base-amount proposal apply unchanged (§14.1). There is one stored shape, the split panel's
-- (SplitEntry/SplitLineDraft): a simple dock entry is a one-line split. A cross-currency entry's
-- header totals are stored as entered; how a booking uses them is the booking run's concern.
--
-- An occurrence is never stored on its own. The only record it leaves is the (template, occurrence
-- date) stamp on the transaction it booked (§14.2).

-- ── recurring_template (the schedule + the split header) ─────────────────────
create table recurring_template (
  recurring_template_id  bigint generated always as identity primary key,
  name                   text not null,
  -- schedule (§14.1)
  start_date             date not null,
  cadence_unit           text not null check (cadence_unit in ('day', 'week', 'month', 'year')),
  cadence_n              int  not null check (cadence_n >= 1),
  end_date               date,                            -- inclusive; "after K" stores the K-th date
  lead_days              int  not null default 0 check (lead_days >= 0),
  confirmation           text not null check (confirmation in ('auto', 'review')),
  booked_through         date not null,                   -- the cursor, in occurrence-date space (§14.3)
  end_reminder           boolean not null default false,
  end_reminder_days      int check (end_reminder_days >= 0),
  management_url         text,
  -- the entry: the split panel's header; lines and tags live in the child tables below
  account_id             bigint references account(account_id),  -- funding account, or …
  person_id              bigint references person(person_id),    -- … funding person
  funding_person_direction text check (funding_person_direction in ('FOR', 'BY')),
  payee_id               bigint references payee(payee_id),
  note                   text,
  spending_currency_code text references currency(currency_code), -- NULL = the funding currency
  funding_total          numeric(19, 4),   -- cross-currency only: the funding-currency total
  base_total             numeric(19, 4),   -- cross-currency, neither leg base: the base total
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  deleted_at             timestamptz,                     -- soft delete
  constraint recurring_template_end_after_start
    check (end_date is null or end_date >= start_date),
  -- exactly one funding source; a funding person always carries its FOR/BY direction
  constraint recurring_template_funding
    check ((account_id is null) <> (person_id is null)),
  constraint recurring_template_funding_person_direction
    check ((person_id is null) = (funding_person_direction is null))
);

-- ── recurring_template_line (one row per split line, mirroring SplitLineDraft) ─
-- account_id is the semantically picked category node (routed to its currency leaf at booking) or,
-- with transfer_direction, the real own account a transfer line hits. A person line carries the
-- person instead; their debt leaf is provisioned at booking, in the line's currency. amount is the
-- line's signed amount as the panel takes it: a bare magnitude, negative for a storno (register §3.8).
create table recurring_template_line (
  recurring_template_line_id bigint generated always as identity primary key,
  recurring_template_id      bigint not null references recurring_template(recurring_template_id),
  account_id                 bigint references account(account_id),
  transfer_direction         text check (transfer_direction in ('TO', 'FROM')),
  person_id                  bigint references person(person_id),
  person_direction           text check (person_direction in ('FOR', 'BY')),
  amount                     numeric(19, 4) not null,
  note                       text,
  sort_order                 int not null,
  -- a line is a category, a transfer (account + direction), or a person (person + direction)
  constraint recurring_template_line_target
    check ((account_id is null) <> (person_id is null)),
  constraint recurring_template_line_person_direction
    check ((person_id is null) = (person_direction is null)),
  constraint recurring_template_line_transfer_direction
    check (transfer_direction is null or account_id is not null)
);

create index recurring_template_line_template_idx on recurring_template_line (recurring_template_id);

-- ── header and line tags (mirror posting_tag, as receipt_line_tag does) ──────
-- Header tags land on the funding leg; each line's tags on its own leg (data-model §10.2).
create table recurring_template_tag (
  recurring_template_tag_id bigint generated always as identity primary key,
  recurring_template_id     bigint not null references recurring_template(recurring_template_id),
  tag_id                    bigint not null references tag(tag_id),
  unique (recurring_template_id, tag_id)
);

create table recurring_template_line_tag (
  recurring_template_line_tag_id bigint generated always as identity primary key,
  recurring_template_line_id     bigint not null
                                 references recurring_template_line(recurring_template_line_id),
  tag_id                         bigint not null references tag(tag_id),
  unique (recurring_template_line_id, tag_id)
);

-- ── the occurrence stamp on transaction (§14.2) ──────────────────────────────
-- Both NULL (hand-entered) or both set (booked from a template). Editing the transaction never
-- changes the stamp, not even its date. The unique index is the only dedup key an occurrence has.
-- It deliberately covers voided rows too: a voided occurrence is the operator's skip and must block
-- its date for good (ADR 0002), unlike the integrity checks scoped to deleted_at is null.
alter table transaction add column recurring_template_id bigint
  references recurring_template(recurring_template_id);
alter table transaction add column occurrence_date date;
alter table transaction add constraint transaction_occurrence_stamp
  check ((recurring_template_id is null) = (occurrence_date is null));
create unique index transaction_occurrence_uq
  on transaction (recurring_template_id, occurrence_date);
