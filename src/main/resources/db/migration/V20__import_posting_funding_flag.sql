-- V20 — mark the synthesised funding leg on a staged transaction (import.md §7; plan e').
--
-- `ImportStagingService` writes each staged transaction as its category/transfer legs in
-- Hauptbuch's sign convention plus one synthesised funding leg on the file's own account carrying
-- the record total (so the legs sum to zero). That funding leg is the number that gets ticked
-- against Money's own balance for the account (the per-account statistics, §9.4) — but it cannot
-- be told apart from an opening-balance self-transfer leg by column values alone: both name the
-- file's own account with a null category. This flag records which leg it is.
alter table import_posting
  add column funding boolean not null default false;

-- Backfill any rows staged before this migration: the funding leg is always inserted last, so it
-- is the highest-id posting of its transaction (staging appends it after the category/transfer
-- legs). Disposable pre-commit staging data, but keep the column consistent with what is there.
update import_posting p
   set funding = true
 where p.import_posting_id = (
   select max(q.import_posting_id)
     from import_posting q
    where q.import_transaction_id = p.import_transaction_id
 );
