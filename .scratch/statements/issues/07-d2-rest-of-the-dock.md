# Statement dock d2: amount differs, wrong account, extras, reconciled-leg notice

Status: resolved (slice d2) — pending owner confirmation of the stage

## Symptom
After d1 only a *missing* line had a dock action. An amount-differs or wrong-account proposal could
not be acted on from the statement page, and an extra could not be edited, moved or voided there.
The register's dock did not say a leg was reconciled by a statement.

## Accepted solution (statements.md §6.4, plan slice d2)
- **Amend** (amount differs, wrong account): every non-exact proposal on a line gets an `Amend:` /
  `Move here:` button that opens the dock on *the booked transaction* (register edit model), with a
  notice saying what Save changes. Save edits that transaction in place — funding leg pinned to the
  bank's signed amount, funding account set to the statement's — and matches + reconciles the leg in
  one database transaction (`/statements/{id}/lines/{line}/amend/{posting}`).
- **Cross-currency amend:** the counterpart amount (and the frozen base amount, when neither leg is
  the base currency) are shown and editable; the funding leg takes the bank's amount, the existing
  commit path re-freezes `base_amount` and the rate write-back records the real rate.
- **Extras:** Edit (the dock, amount editable, nothing matched), Move (select an open own account of
  the *same currency*, everything else kept) and Void (`hx-confirm`), all through `operations`.
- **Register dock:** `ledger.ReconciledLegNotices` (implemented by `statements`) lets the edit dock
  show `BankAaa-EUR leg reconciled — statement 2026-05`, muted, never a block.

## Limits (deliberate, each says "use the register" in the UI)
- Only simple two-leg transactions open in the dock (the register's own limit; splits go to the
  register). Split creation from a missing line is issue 06.
- Amend and extra-edit need the statement's account to be the transaction's *funding* leg; the
  other end of a transfer is refused.
- A cross-currency *extra* is edited in the register (amend of a cross-currency proposal is here).
- A foreign line's original amount is not pre-filled — deferred to the PDF stage (e), as in d1.
- Amending a leg another statement already matched drops that match (ADR 0003: an amount change
  drops `reconciled`); the dock's notice says so.

## Refactor
`StatementController` lost the dock saves to `StatementDockController`; the page model is built by
`StatementPageAssembler` so a refused save can re-render the page with the dock open.

## Review follow-ups
- A cross-currency amend whose bank figure differs from the booked one opens with the base amount
  blank (the frozen value belonged to the old figure); the operator confirms a new one.
- The register dock keeps the reconciled-leg notice when a save is refused.
