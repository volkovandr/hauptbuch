-- Stage 9g — the header Note the operator writes on the post-process screen, copied to
-- transaction.note at Confirm (data-model §13.1). Distinct from ai_note, which is *input* to the
-- parse ("this is fuel"); this is the note that ends up on the booked transaction.
--
-- The header's other 9f omission, Receipt no., needs no column: the parsed receipt_number is simply
-- made editable and saved by the same header update.

alter table receipt add column note text;
