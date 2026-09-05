-- V23 — widen `exchange_rate.source` to admit `'import'` (data-model §3.7; import.md §6.3; plan e3).
--
-- A resolved cross-currency transfer (automatic match, manual match, or a hand-entered far amount —
-- e2a/e2b) supplies two real native amounts for the same event. Whenever one of the two currencies
-- is the book's base currency, that pair *is* the actual conversion rate for the date — a genuine
-- fact, not a feed lookup or a guess — and `ImportMirrorRepository` writes it back into this cache
-- so it is available to propose future rates and revalue held balances (§3.7). It never overwrites
-- an existing row for that `(currency_code, date)` (`on conflict do nothing`): an ECB or manual rate
-- already on file for that day is left alone.
alter table exchange_rate
  drop constraint exchange_rate_source_check;

alter table exchange_rate
  add constraint exchange_rate_source_check
  check (source in ('ecb', 'manual', 'import'));
