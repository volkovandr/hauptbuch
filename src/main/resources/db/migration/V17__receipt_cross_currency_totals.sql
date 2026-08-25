-- Issue receipts/23 — a receipt billed in one currency, paid from an account in another. The header
-- gains the register split panel's two cross-currency totals (data-model §13.1): what actually came
-- off the paying account, and the base-currency figure that freezes the conversion.
--
-- Both stay NULL for a single-currency receipt (the ≥95% case), which books through the untouched
-- path. funding_total is an operator-overtypeable *estimate* at review time — the card's real charge
-- is on a statement that has not arrived — while base_total is the frozen base fact the postings
-- carry (data-model §6.4), which is why it is persisted rather than re-derived from today's rates.

alter table receipt add column funding_total numeric(19, 4);
alter table receipt add column base_total numeric(19, 4);
