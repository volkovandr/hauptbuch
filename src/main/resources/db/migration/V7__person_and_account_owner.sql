-- V7 — person & account_owner (stage 8a: per-person debts foundation)
--
-- A person is a contact; their per-person debt accounts (one per currency, type asset, allowed to
-- go negative) are linked back to them via account_owner. No parent account exists for the
-- per-person leaves — they are standalone assets grouped by the account_owner → person link
-- (data-model §7, §3.3).
--
-- A person may have multiple accounts — one per currency (Max-EUR, Max-CHF, etc.). Grouping is
-- entirely via the account_owner link, never by naming. Leaf names are cosmetic; display resolves
-- the person's name via the link.
--
-- Soft-delete is reversible; a soft-deleted person is hidden from pickers but keeps all history
-- and may be revived by confirmation when the name is re-entered.

-- ── person (a contact — data-model §3.3) ──────────────────────────────────────
create table person (
  person_id  bigint generated always as identity primary key,
  name       text not null,
  deleted_at timestamptz
);

-- Lookup by name for pickers/revival (many-to-one: multiple live persons can have the same name).
create index person_name_idx on person (name);

-- ── account_owner (junction: links an account to the person who "owns" it) ────
-- A per-person debt account (type asset, allowed to go negative) is linked via this row to the
-- person who owns it. One owner per account (a given account is never "owned" by two people);
-- many accounts per person (one per currency: Max-EUR, Max-CHF).
create table account_owner (
  account_owner_id bigint generated always as identity primary key,
  account_id       bigint not null references account(account_id),
  person_id        bigint not null references person(person_id),
  unique (account_id)                       -- one owner per account
);

-- Lookup by person to find all their accounts.
create index account_owner_person_id_idx on account_owner (person_id);
