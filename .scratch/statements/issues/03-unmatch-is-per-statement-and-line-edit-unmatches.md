# Unmatch is per statement; editing a matched line unmatches it

Status: resolved (slice c2)

## Symptom
Two behaviours of c2 surprised the owner on review:
1. Unmatch on one statement removed the posting's matches on *every* statement.
2. The first fix for "editing a matched line's date or amount leaves a stale match" refused the edit.

## Root cause
1. The first c2 reading of statements.md §5 ("a match exists only on a `reconciled` posting") made
   Unmatch strip all matches of the posting. That invariant only needs the posting to *stay*
   `reconciled` while any match remains; it does not need Unmatch to reach other statements.
2. Refusing the edit puts the burden on the operator, who has to unmatch first.

## Accepted solution (owner's decisions)
- **Unmatch affects only the statement it is done on.** The posting goes back to `unreconciled` only
  when no other statement still matches it (the same rule as deleting a statement with reset).
- **An edit made in the register** that drops a posting out of `reconciled` still removes its
  matches on every statement (§5) — that is a change to the ledger, not to one statement.
- **Saving the line grid** with a changed booking date or amount on a matched line removes that
  line's match and, unless another statement still matches the posting, sets it `unreconciled`.
  Edits to counterparty, description or bank category never touch a match.
- No modal. A muted note beside **Save lines** says so: "Saving a changed date or amount on a
  matched line removes its match and makes its posting unreconciled."

## Left as is (owner's decisions)
- Confirming a `pending_review` transaction by a match does not touch receipt data; a zero-amount
  receipt placeholder later edited in the register may diverge from the receipt — accepted.
- `statement_line_exclusion.statement_line_id` has no `on delete`; revisit when e3 (re-seed) lands.
- No row locking between review and match (single user).
