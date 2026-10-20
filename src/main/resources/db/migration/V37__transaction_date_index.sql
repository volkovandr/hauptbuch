-- V37 — index the transaction date for the statement matcher (.scratch/statements/issues/15).
--
-- The matcher joins every statement line to the live transactions inside a date window around its
-- booking date (statements.md §4.1). With no index on transaction.date that is a scan of the whole
-- register per line — seconds on a 27k-transaction book. Partial on live rows: every reader of this
-- window filters on deleted_at is null.
create index transaction_date_idx on transaction (date) where deleted_at is null;
