-- V2 — seed reference data: the currencies actually used and the system accounts.
--
-- Per data-model §3.1 we seed only the currencies in use, not all ~180 ISO codes. The engine's
-- examples (and the owner's life) live in EUR and CHF; USD/GBP round out the common foreign cases,
-- and JPY is the zero-minor-units case the rounding path must handle.
--
-- System accounts (plan stage 3): the Opening Balances equity tree. This is the only seeded system
-- tree — the engine resolves it by name when recording an opening balance, so it must exist for
-- every currency. (`FX gain/loss` was once seeded too, but with the auto-booking retired in 7d.0 no
-- code resolves it by name; it is now a plain user category created on demand — data-model §6.3.)
-- It follows the per-currency-leaf category rule (data-model §6.5): a semantic parent with one leaf
-- per currency, because every account — categories and system accounts included — has exactly one
-- currency. Postings hit the leaves; the parent is a pure rollup (leaves-only, §5).
--
-- Why per-currency leaves when base currency is still unset here: the seed cannot know which
-- currency will become base (it is set at first run, write-once). Seeding a leaf per currency means
-- the engine always finds the leaf it needs — the base-currency Opening Balances leaf for an
-- opening balance, a base-denominated fact — whatever base currency the book is later locked to.

-- ── currencies ───────────────────────────────────────────────────────────────
insert into currency (currency_code, minor_units, symbol, name) values
  ('EUR', 2, '€',   'Euro'),
  ('CHF', 2, 'CHF', 'Swiss Franc'),
  ('USD', 2, '$',   'US Dollar'),
  ('GBP', 2, '£',   'Pound Sterling'),
  ('JPY', 0, '¥',   'Japanese Yen'),
  ('CZK', 2, 'Kč',  'Czech Koruna'),
  ('PLN', 2, 'zł',  'Polish Zloty');

-- ── Opening Balances (equity) — anchors opening balances (data-model §3.2, T-DM-4) ──
insert into account (name, type, parent_id, currency_code)
  values ('Opening Balances', 'equity', null, 'EUR');

insert into account (name, type, parent_id, currency_code)
select 'Opening Balances ' || c.currency_code, 'equity', p.account_id, c.currency_code
from currency c
cross join (select account_id from account where name = 'Opening Balances' and parent_id is null) p;
