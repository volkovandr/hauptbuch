# Statements: "Move" on an extra does nothing for a multi-leg (split) transaction

Status: resolved
Category: bug
Area: `statements` — `StatementExtraDockService.move` / `editableModel`, `StatementDockController.moveExtra`, `statement.html` (extras table)

## Symptom
In the statement page's **extras** table, picking another account and pressing **Move** works when
the extra's transaction has two legs (the statement account + one counterpart). When the transaction
has more than one counterpart leg (a split), pressing Move appears to do nothing: the extra stays on
the same account and no error or warning is noticed.

## Expected
The extras row is one posting on the statement's account. Move only re-books *that leg* onto another
account, so it should work whatever the other legs of the transaction look like.

## Root cause (confirmed in code)
`StatementExtraDockService.move` does not move the leg. It loads the whole transaction through the
dock's edit model and re-saves it:

1. `editableModel(...)` → `DockEditService.load(transactionId)`. `DockEditService.classify` accepts
   only exactly two postings (`EXPECTED_LEG_COUNT`); anything else throws `IllegalArgumentException`
   (`NOT_EDITABLE`), which `editableModel` rewraps as `RegisterOnlyException` ("Only a simple
   two-leg transaction can be changed here — this one is a split…"). The same method also refuses a
   cross-currency transaction and one whose other end is the statement account.
2. Otherwise `DockForms.editEntry(DockForms.inputOf(model), …, target.accountId(), model.amount())`
   rebuilds the transaction from the dock model and commits it.

So Move inherits every restriction of the dock's *Edit*, although re-booking one leg needs none of
them.

## Why nothing visible happens
`RegisterOnlyException` extends `StatementFormatException`, so `StatementDockController.flash`
catches it and stores it as the `error` flash attribute, then redirects. `statement.html` renders it
in `<p class="statements__error">` at the **top** of the page (line ~50), while the htmx form swaps
`main` in place and keeps the scroll position at the extras table. The message is therefore most
likely rendered off-screen. (Not reproduced in a browser — this part is inferred from the markup.
Verify when fixing.) Unlike the Edit link, Move does not offer the register link
(`refusalTransactionId`) either.

## Accepted solution (owner, 2026-10-24)
Implemented as below; reconciliation follows the existing two-leg behaviour (the moved leg ends
`unreconciled`, its match is dropped through the `ReconciliationDropListener`). An extra is never
`reconciled` anyway (statements.md §6.3). **Not yet verified by the owner.** `./gradlew check` is green (2026-10-24).
The "surface the refusal near the row" point below is deliberately **not** done: few refusals remain.

### Design
- Make Move a **leg-level** operation: change `account_id` of the one posting `postingId` to the
  target account, in place (ADR 0003: postings are edited in place, so `posting_id` survives), via
  the ledger/operations layer instead of the dock edit model. No need to understand the other legs.
- Keep the guards that still apply to a single leg: target is an open asset/liability account, not
  the current account, same currency as the leg (a currency change would break per-currency
  sum-to-zero / `base_amount` rules). Respect the "one leg per real own account per transaction"
  rule: refuse when the target account already has a leg in that transaction.
- Reconciliation: the moved leg leaves the statement account, so any `reconciliation`/match on it
  must be handled as the existing single-leg path does today (verify, do not guess).
- Surface any remaining refusal where the operator is looking (next to the row, or scroll to the
  message), not only at the top of the page.

## Tests to add
- Unit (`StatementExtraDockServiceTest`): Move on a split transaction succeeds; the other legs are
  untouched; refusal cases (same account, other currency, target already in the transaction).
- Repository/integration: the leg-level account update round-trips and keeps `posting_id`.
- MockMvc: a refused Move shows its message in the rendered page.
