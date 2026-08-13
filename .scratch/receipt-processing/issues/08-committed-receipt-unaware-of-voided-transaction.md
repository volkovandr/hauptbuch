# A committed receipt has no idea its linked transaction was voided from the register

Status: needs-triage
Category: bug
Severity: medium
Area: Receipts — processing screen, committed view (`receipt-process.html`) × register void
(`RegisterEntryController`/`LedgerService`/`ReceiptProcessingController`, plan §9g)

## Symptom (owner report)

Scan a receipt, process it, commit it into the register — all good, receipt is now `committed` and
linked to a transaction. Separately, from the register, void that same transaction (a normal,
valid action). Go back to the receipt: nothing on the committed receipt screen indicates the
linked transaction is gone. "Edit transaction" is still offered, but clicking it jumps to the
register and silently fails to open edit mode — no error, no dock, no highlighted row, nothing.
The receipt is stuck: no indication of the void, and no way to re-enter it or otherwise act on it
the way a receipt normally allows.

## What exists today

- `receipt.transaction_id` (`V9__receipt.sql:30`) is a plain FK, no cascade, no trigger. It is set
  once, at Confirm, by `ReceiptCommitService.confirm` → `receiptRepository.markCommitted(id,
  transactionId)` (`ReceiptCommitService.java:78-79`; SQL at `ReceiptRepository.java:195-205`), and
  never re-validated afterward.
- `ReceiptState` (`ReceiptState.java`) has no state or flag for "linked transaction was voided."
  `committed` stays `committed` regardless of what later happens to the transaction it points at —
  the only things that move a committed receipt off that state are actions initiated from the
  receipts side itself (`reopen`, Re-enter, `deleteCommitted`).
- Voiding a transaction is a soft-delete via the `deleted_at` axis: `LedgerService.voidTransaction`
  (`LedgerService.java:139-146`) → `transactionRepository.softDelete`. It does nothing else — no
  callback or query into `receipts`.
- Two paths reach it:
  - **Via receipts**: `ReceiptCommitService` → `DockCommitService.voidTransaction`
    (`DockCommitService.java:309-312`), used by Re-enter and the committed-delete dialog. These
    *do* coordinate with the receipt row (e.g. Re-enter overwrites `transaction_id` with the fresh
    one).
  - **Directly from the register, bypassing receipts entirely**: `RegisterEntryController.voidTransaction`
    (`POST /register/void`, `RegisterEntryController.java:216-235`) calls
    `dockCommitService.voidTransaction(form.transactionId())` for whatever transaction is loaded in
    the dock's edit mode. **This path never looks up or touches any `receipt` row**, even when one
    is linked via `transaction_id`. This is the gap the owner hit.
- On the committed receipt screen (`receipt-process.html:366-382`), the "Edit transaction" link is
  guarded only by `receipt.transactionId() != null` — not by any check that the transaction is
  still live — and points at `@{/register(selected=${receipt.transactionId()})}`.
- `GET /register?selected=...` → `RegisterController.register` (`:57-90`) →
  `RegisterJumpService.filterForTransaction` (`RegisterJumpService.java:37-46`) →
  `RegisterRepository.findOwnLegs` (`:177-193`), which is scoped `where ... t.deleted_at is null`.
  A voided transaction has `deleted_at` set, so this returns zero rows, the jump resolves to
  `Optional.empty()`, and `RegisterController` deliberately falls back to the plain default view
  (`:77-78`, its own comment: "A voided or unknown id falls back to the default view — and must not
  then dock a transaction the register cannot show"). `selectedTransactionId` stays `null`, so
  `register.html` (`:280-281`) never fires the dock-load request. No error, no toast — it looks
  identical to the default register.
- Nowhere in the `receipts` module is `transaction.deleted_at` (or `lifecycle`) ever queried. The
  committed-delete dialog (`CommittedDeleteChoice`, offering void/keep × keep-files/remove-files)
  also doesn't check whether the transaction is already voided before unconditionally offering to
  void it again.

## Root cause

`receipt.transaction_id` is a fire-and-forget FK, and transaction voiding from the register
(`POST /register/void`) is entirely ledger-local with zero coupling back to `receipts`. Nothing
ever notices or records that the two have diverged. The only symptom a user can observe is a dead
"Edit transaction" link that degrades silently to the default register view, because the register
jump helper is (correctly, for its own purposes) scoped to live transactions and treats "can't
resolve" the same whether the id is voided or simply bogus.

## Comments

Filed 2026-08-13 from an owner report: committing a receipt, then voiding its linked transaction
from the register directly (not via the receipt), leaves the receipt stuck in `committed` with a
dead "Edit transaction" link and no visible indication anything is wrong. Owner wants the receipt
to surface that its transaction was voided and to regain the ability to act on the receipt (e.g.
re-enter it) — left at `needs-triage`: whether that's a live check at render time, a reconciling
flag/state, or something else is a design call for the owner, not baked into a brief unprompted.

> *This was generated by AI during triage.*
