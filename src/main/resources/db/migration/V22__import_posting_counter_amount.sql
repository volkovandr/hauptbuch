-- V22 — the far-currency amount on a resolved cross-currency transfer leg (import.md §6.2/§6.3;
-- plan e2a).
--
-- A cross-currency transfer parks on first sighting: QIF carries no far-side amount, and whether a
-- transfer even *is* cross-currency depends on the account map (the file's account currency vs the
-- named account's currency), known only after slice c. It is built only once the real far amount is
-- known — from the mirror sighting (e2a) or entered by hand for a counterparty whose file will
-- never arrive (§6.4; e2b).
--
-- `amount` stays the near-currency figure the file stated (the funding leg's currency); this column
-- carries the same leg's amount in the *target* account's currency, same sign as `amount`. It is
-- the mirror sighting's funding-leg amount — the far side's own file is authoritative for its own
-- currency. Null on every other leg, and on an unresolved park.
--
-- `base_amount` (the frozen base-currency fact, data-model §3.6) is deliberately NOT set here: that
-- is e3, where the two real native amounts *are* the conversion rate for the date and get written
-- back to `exchange_rate` (source = 'import').
alter table import_posting
  add column counter_amount numeric(19, 4);
