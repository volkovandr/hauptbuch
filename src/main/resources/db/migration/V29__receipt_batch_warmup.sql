-- V29 — receipt.warmup_batch_id (receipt-processing/31 v3: a real 1-item batch primes the
-- Batches API's cache, since a standalone synchronous max_tokens:0 call never worked — the
-- Batches API dispatches independently of the synchronous Messages API and does not read its
-- cache writes, confirmed both against Anthropic's docs and the owner's own tests).
--
-- NULL for every receipt outside the warm-up window. A non-null value means: this receipt is
-- claimed and processing, but not yet a member of any batch (batch_id stays null) — it is queued
-- behind the named batch, and the poller submits it for real once that batch ends (whether it
-- succeeded or not). The 9e startup sweep must leave these alone too, same as a real batch member.
alter table receipt add column warmup_batch_id text;
