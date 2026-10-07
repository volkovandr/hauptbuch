# Overlap "a different transaction" needs a stored decision

Status: resolved (slice c2)

## Symptom
statements.md §4.4: when a line's best candidate is already matched on another statement, the
operator decides — *the same bank movement* (match again) or *a different transaction that looks
the same* (the line is missing and gets created). Matching is otherwise live and stores nothing
(§4.5), so the second answer had nowhere to live: the next view would offer the same candidate again.

## Root cause
The design stores only matches; a *rejection* is a fact about a (line, posting) pair that cannot be
recomputed.

## Accepted solution (slice c2)
- Table `statement_line_exclusion (statement_line_id, posting_id)`, unique pair, posting FK cascades
  (V34; data-model §15). The candidate query leaves excluded pairs out, so the line falls to its next
  candidate or to *missing*.
- "Same movement" is the ordinary Accept (a second match on the posting, a no-op on its state).
- Accept in c2 only confirms **equal-amount candidates on the statement's own account**
  (`exact`/`ambiguous`/`overlap`). Amount-differs and wrong-account candidates change the ledger
  and therefore go through the dock (slice d2).
- Unmatch removes that statement's match and, unless another statement still matches the posting,
  sets it `unreconciled` (revised by `03-*`). Deleting a statement with "reset" un-reconciles only postings no other statement
  still matches.
