-- Paying-account detection widened from one card last-4 to a comma-separated label vocabulary
-- (data-model §13.4): a single four-digit string matched almost nothing, since the AI names the
-- payment line freely and a bare 'card' carries no digits at all.
--
-- A rename, not a new table: labels carry no identity and are deliberately not unique. Existing
-- values carry over untouched — a stored '1234' is already a valid one-label list.

alter table account rename column card_last4 to detection_labels;
