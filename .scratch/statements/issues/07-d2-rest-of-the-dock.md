# Statement dock d2: amount differs, wrong account, extras, reconciled-leg notice

Status: resolved (slice d2)

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

## Owner testing, round 1
- **Page jumped to the bottom** on every statement-page action (Create, Save, Move here). Cause:
  Chrome scroll anchoring re-anchors after htmx replaces the whole `<main>`, visible once the page
  had extras below the lines. Fixed with `overflow-anchor: none` on the statement page's `<main>`;
  `StatementDockBrowserTest.openingAndSavingTheDockDoesNotMoveThePage` guards it.
- **Fix buttons were as wide as the candidate text** ("Move here: 29.09.2026 Kpler 337,88 on …").
  They now read just "Amend" / "Move here"; the candidate is already listed in the line's details.
- **Round 2:** "Move here" still wrapped (the `.btn` could shrink in the narrow actions cell) —
  buttons in the statement table are now `nowrap`. Amend on a split looked like a no-op: the refusal
  was shown at the top of the page, out of view. It now appears on the line's (or extra's) own row,
  worded for the statement page ("change it in the register").
- **Round 3:** the "use the register" refusals (split, cross-currency extra, far end of a transfer)
  throw `RegisterOnlyException`, and the refusal row ends in a "Change it in the register" link to
  `/register?selected=<transaction>`, which opens that transaction in the register's edit dock.
  Focus moves to the dock's Date on open.
- **Round 3, wish:** a perfectly matched transaction could only be unmatched or re-matched, never
  edited. Matched lines get an "Edit in register" button in their own column, linking to
  `/register?selected=<transaction>` (the register's jump opens the edit dock).
- **Round 3:** the extras' Move form stacked its account picker above the button. Edit, Move
  (picker left of its button) and Void now sit on one line; the column header has a help marker
  saying what each does.
