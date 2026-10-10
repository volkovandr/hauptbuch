# Statement dock: pre-fill the foreign charge of a line

Status: resolved

## Symptom
A card purchase priced in USD on a EUR statement has all its facts on the line — the bank amount in
the account's currency, the original amount, the original currency (and a rate). Create opens the
dock with no way to say the purchase was in USD, so it books as a plain EUR purchase. Entering the
same purchase in the register (category-currency override) books and matches correctly.

## Root cause
statements.md §6.4 says "a foreign line pre-fills the cross-currency fields from its original
amount", but the dock only offers the counterpart-amount fields for a transfer into another
currency (issue 09). A line's original amount and currency are stored (`statement_line.original_*`)
but never read back by the dock.

## Accepted solution (owner, 2026-10-10) — pre-fill only
- When a missing line has an original amount and currency different from the account's, the dock's
  existing counterpart fields appear pre-filled: **Counterpart amount (USD)** = the original amount
  (a magnitude), **Base amount** only when neither currency is the base currency (proposed as for a
  transfer). The funding leg stays pinned to the bank's amount; the save is the existing
  cross-currency commit, so the category's USD leaf is used and the rate is recorded as usual.
- The fields are there **from the moment the dock opens** (the line already knows the charge) and
  stay when a category is picked or the Category field is cleared. (First cut showed them only after
  a category was picked — owner: wrong, 2026-10-10.) A transfer to an account in the original
  currency takes the original amount instead of the rate-proposed one.
- No free currency selector in the dock; the line's own currency is the only offer.
- The rate printed on the line is not used: the real rate follows from the two amounts.
