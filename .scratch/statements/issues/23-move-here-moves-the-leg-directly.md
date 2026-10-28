# Statements: "Move here" moves the leg in one click; "Edit in register" on every candidate

Status: open
Category: enhancement
Area: `statements` — `StatementReviewViews` (`fixLabel`, `registerTransactionId`), `StatementMatchService` / `StatementDockController`, `statement.html` (line rows)
Design: `docs/statements.md` §6 (wrong-account tier, "the existing re-threading")

## Symptom
A line whose best candidate is on **another account** (the wrong-account tier) offers *Move here*.
It opens the inline dock, and saving re-threads the whole transaction through the dock's edit model.
For a **split** transaction the dock refuses ("Only a simple two-leg transaction can be changed
here…"), so the commonest mistake — a transaction entered on the wrong bank account — cannot be
fixed from the statement page.

## Root cause
*Move here* goes through the same path as *Amend* (dock → `DockEditService` → rebuild and re-save),
which accepts only a plain two-leg, single-currency transaction. Re-booking one leg needs none of
that: `LedgerService.moveLeg` already does it in place for any shape of transaction (it is what the
extras' *Move* uses, issue 18; ADR 0003 keeps `posting_id`).

## Accepted solution (owner, 2026-10-28)
- **Move here becomes one click, no dock:** a POST that moves the candidate's leg to the statement's
  account with `LedgerService.moveLeg` and matches it to the line (the same match step *Accept*
  uses), in one transaction. The wrong-account tier only offers postings that are not `reconciled`,
  so nothing is dropped.
- **Refusals** (target already has a leg in the transaction, different currency) surface as today's
  flash error, with the register link below so the operator can finish by hand.
- **Edit in register on every candidate row**, not only matched ones — the escape hatch for
  anything that "needs attention" (a split whose counterpart legs are also wrong, a wrong date…).
  Same link the matched rows use (`/register?selected=<transactionId>`).
- *Amend* is unchanged (it still opens the dock: the amount or date differs, so the operator must
  look).

## Implemented
2026-10-28 on `claude/determined-keller-pl1848`, awaiting the owner's verification. `StatementMatchService.moveHere`
(`POST /statements/{id}/lines/{line}/move-here`) checks the live review (wrong-account proposal only),
calls `LedgerService.moveLeg` to the statement's account, then confirms the match like *Accept*; a
ledger refusal becomes the usual flash error and nothing changes. `StatementReviewViews`: `fixes`
now holds only *Amend* (amount differs), the new `moves` holds *Move here*, and
`registerTransactionIds` replaces `registerTransactionId` — the match's transaction, else every
distinct candidate transaction, each rendered as an *Edit in register* link.
- The wrong-account tier already excludes `reconciled` postings (§4.2), so a move drops nothing.
- The dock's own wrong-account handling (`DockEditService` re-threading, "saving moves it to…") is no
  longer reached from a button; left in place, to be removed separately if nothing else uses it.
- A row with several candidates shows one *Edit in register* link per transaction, unlabelled.
