# Statement dock: a transfer to an account in another currency

Status: resolved — pending owner confirmation

## Symptom
On a EUR account's statement, Create on a line that is a transfer to a USD account refuses to save
("a USD amount is required"): the commit path books a cross-currency transaction and needs the
counterpart's native amount, but the dock only showed the counterpart/base fields when *amending* a
booking that was already cross-currency. The EUR bank cannot know the target is USD, so the operator
has nothing to type from the statement.

## Accepted solution (owner, 2026-10-09)
- After the Category field resolves a transfer target in another currency, the dock shows
  **Counterpart amount (USD)**, pre-filled from the rate feed (`prefillFundingTotal`: the bank's
  amount converted at the line's date; blank — never a guess — when no rate is on file), plus
  **Base amount** only when neither account is in the base currency. Both editable.
- The funding leg stays pinned to the bank's amount. **Save is not blocked on the suggestion — Save
  is the confirmation.** If the figure is wrong it is corrected when the other account's statement
  is matched (the d2 Amend flow).
- `GET /statements/{id}/lines/{line}/cross-currency` renders the fields; the dock refreshes them
  whenever the category resolution swaps. Picking something that is not a cross-currency transfer
  clears them.
- Categories and persons are unaffected: their currency leaf follows the paying account.
