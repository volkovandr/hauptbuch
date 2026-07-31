-- V10 — stage 9c pre-process: the edit recipe, and retiring the `discarded` state (data-model §13.1)
--
-- Pre-processing bakes a client-side-edited image (the copy the AI receives) and stores the
-- parameters that produced it — crop box / rotation / tilt / filter values, as JSON — so a later
-- re-edit can replay them onto the immutable original (receipt doc §6.1). A note-only tweak must
-- not cost the crop; the recipe makes the edit reproducible.
alter table receipt add column edit_recipe text;   -- client-side edit parameters (JSON); NULL until first Save

-- Retire `discarded` (2026-07-31, 9c grilling). It meant "looked and chose not to book, kept
-- visible"; that need is now a soft-delete with files kept (the row is soft-deleted, its images
-- stay on disk), and "Discard" is repurposed to the processing screen's stage-undo. Any lingering
-- discarded row is normalised to a soft-deleted `new` row — invisible, images retained — so the
-- tightened check constraint applies. `state` and `deleted_at` stay orthogonal (§3.5).
update receipt
   set deleted_at = coalesce(deleted_at, now()),
       state = 'new'
 where state = 'discarded';

alter table receipt drop constraint receipt_state_check;
alter table receipt
  add constraint receipt_state_check
  check (state in ('new', 'pre_processed', 'processing', 'processed', 'committed', 'failed'));
