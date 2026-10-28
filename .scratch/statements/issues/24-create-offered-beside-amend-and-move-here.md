# Statements: Create offered beside Amend and Move here

Status: in progress
Category: enhancement
Area: `statements` — `LineStatus.canCreate`, `StatementReviewViews`, `StatementDockService` (`creatableLine`)
Design: `docs/statements.md` §4.4, §6

## Symptom
A line whose only candidate is a transaction with a **different amount** (*Amend*) or on **another
account** (*Move here*) offers just that one fix. When the candidate is correct — it is matched on
another statement, or simply a different transaction — the line is really *missing* and needs a new
transaction, but *Create* is shown only for a line with **no** candidate at all.

## Root cause
`canCreate` was `status == MISSING`, and the dock's create/commit path (`missingLine`, now
`creatableLine`) refused any other status.

## Accepted solution (owner, 2026-10-28)
- *Create* is offered on an unmatched line in status `MISSING`, `AMOUNT_DIFFERS` or `WRONG_ACCOUNT`,
  beside *Amend* / *Move here*. It is the ordinary create dock and commit, unchanged.
- No exclusion is written: the new transaction is an exact match, so the line becomes `matched`;
  the old candidate stays as it was (unmatched, so it shows among the extras or waits for another
  line).
- An exact match already on another statement is covered by *Different transaction* (§4.4) and is
  unchanged; so are `EXACT`, `COMPETING` and `AMBIGUOUS`, which offer Accept / pick, not Create.

## Tests to add
- MockMvc: an amount-differing and a wrong-account line render *Create*; creating books a new
  transaction, matches it, and leaves the candidate transaction untouched and unmatched.
