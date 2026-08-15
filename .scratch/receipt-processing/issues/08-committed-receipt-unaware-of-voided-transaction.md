# A committed receipt has no idea its linked transaction was voided from the register

Status: resolved
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

---

> *This was generated by AI during triage.*

## Triage session (2026-08-15)

**Redundancy check:** no existing code reconciles `receipt.transaction_id` against
`transaction.deleted_at`. The two existing void-aware receipt paths (re-entry, the 5-way
committed-delete dialog) only handle a void the receipt *itself* initiates — never a void that
happens the other direction, from the register. Not already implemented.

**Prior-rejection check:** no `.out-of-scope/` entries exist yet in this repo.

**Claim verified against current code** (traced end to end, not just re-read): confirmed real. A
`committed` receipt's "Edit transaction" link degrades silently to the plain default register
view when its transaction is voided, because the register's jump-resolution is (correctly, for
its own purpose) scoped to live transactions and can't distinguish "voided" from "bogus".

**A sharper bug surfaced during investigation, folded into this same issue (owner-approved):**
Reopening a `committed` receipt is already completely transaction-state-agnostic — it just flips
the receipt back to `processed` and never inspects the transaction. The real dead end is one step
later: re-entering (Confirm after Reopen) unconditionally tries to void the receipt's *existing*
linked transaction before booking the new one, and voiding an already-voided transaction is
rejected as an error by the ledger's void operation. So Reopen appears to work, but finishing
Re-entry on such a receipt fails every single time, surfaced only as a generic "something went
wrong" toast — a guaranteed dead end dressed up as a transient error. The identical unconditional
void call exists on the 5-way committed-delete dialog's void axis.

**Design grilled to full resolution — see also `CONTEXT.md`'s new "Void (badge)" glossary term:**

1. Both places that unconditionally void the receipt's existing linked transaction (re-entry, and
   the delete dialog's void axis) must tolerate the target already being voided — treat it as
   already-satisfied rather than an error.
2. Detection is a **live check** (query transaction liveness where needed), never a stored flag on
   the receipt — no schema change, no new module coupling; matches the project's standing
   "compute on the fly" convention. For a single receipt this is a one-id lookup; for a list (PC
   register or mobile grid) it's **one batched lookup covering every visible row**, following the
   exact precedent already established by the register's Merchant-column fix (a single batched
   query feeding a per-row display map, never a per-row query).
3. The check runs **on every render** of anywhere a committed receipt's state is shown (the
   single-receipt pane, the PC list, the mobile grid) — not lazily on click — so the void is
   visible up front rather than discovered by hitting a dead link.
4. **Single-receipt pane:** once known voided, hide the "Edit transaction" link (it's a
   confirmed-dead jump) and show an inline note in its place explaining the transaction was
   voided from the register and that Reopen is how to re-enter it. Reopen itself needs no change.
5. **The 5-way committed-delete dialog:** no UI change — once (1) lands, choosing "void" when
   already voided is a harmless no-op with no visible difference in outcome. (A "(already voided)"
   label there was considered and explicitly deferred as a follow-up, not blocking this issue.)
6. **The register's void action itself is untouched** — no new friction/warning when voiding a
   transaction that has a linked receipt. The owner's original report treats that void as "a
   normal, valid action"; this issue is about the receipt noticing and recovering, not the
   register gaining new awareness.
7. **PC list is filterable for this**, without adding a new `receipt` state: a new option in the
   existing single State filter dropdown (not a second, separate filter control) that selects
   committed receipts whose transaction is voided.
8. **Visual styling, PC list and mobile grid alike:** grey, labelled/titled exactly **"Void"**
   (replacing "Committed", not appended to it) — short and consistent across both surfaces, distinct
   from the single-receipt pane's inline note (which stays a full explanatory sentence, since it's
   explaining a situation rather than labelling a row).
9. **Mobile's existing state dot** (confirmed still showing all states including `committed`,
   per the doc's stage-9b widening — a live check against the current codebase confirmed this,
   correcting an initial assumption otherwise) gets the same grey/"Void" treatment as the PC
   badge, for the same reason: leaving it showing a stale "committed" dot would undercut the
   whole point of this fix.

## Agent Brief

**Category:** bug
**Summary:** A `committed` receipt gives no indication, and no reliable path forward, once its
linked transaction is voided from the register — surface it everywhere the receipt's state is
shown, and fix the guaranteed failure on the one existing recovery path.

**Current behavior:**
A receipt reaches `committed` by being confirmed, which links it to a transaction. If that
transaction is later voided directly from the register (a normal, valid, unrelated action, not
routed through the receipt at all), nothing on the receipt side notices:
- The single-receipt pane still offers "Edit transaction," which silently lands on the plain
  default register view instead of the transaction (no error, no explanation).
- The PC list and the mobile browse grid both keep showing the receipt with its ordinary
  "committed" state, indistinguishable from a receipt whose transaction is still live.
- Reopening the receipt (back to `processed`, for editing) works regardless of the transaction's
  state — but finishing that recovery by re-entering (Confirm) unconditionally tries to void the
  receipt's *existing* transaction first, which fails every time because it's already voided,
  surfacing only as a generic, unhelpful error. The receipt is then permanently stuck oscillating
  between Reopen and a failing Confirm.
- The 5-way committed-delete dialog has the identical unconditional-void failure mode on its void
  axis.

**Desired behavior:**
- Voiding the receipt's existing linked transaction, wherever the receipt-side flow does it
  (re-entry on Confirm, and the committed-delete dialog's void axis), must succeed whether or not
  that transaction was already voided by something else — "make sure no live transaction remains
  linked" is the actual intent, and that's already true if it's voided.
- Anywhere a committed receipt's state is displayed — the single-receipt pane, the PC list, and
  the mobile browse grid — must reflect, at render time, whether its linked transaction is
  currently live or voided. This is never persisted; it's derived fresh each time, the same way
  other cross-entity display facts in this codebase already are (batched into one query per list
  render, never a query per row).
- The single-receipt pane, when the linked transaction is voided: the "Edit transaction" link is
  not shown (it would be a confirmed-dead jump); a short explanatory note takes its place,
  identifying that the transaction was voided from the register and that Reopen is the way to
  re-enter it. No change to Reopen's own behavior or the Confirm/"Re-enter" wording.
- The PC list's state badge and the mobile grid's state dot both show a distinct, grey "Void"
  indicator (in place of "Committed") for exactly this case, and nowhere else.
- The PC list's existing single State filter gains one additional selectable option that narrows
  to committed receipts whose transaction is voided — without introducing a new `receipt` state
  value and without adding a second, independent filter control.

**Key interfaces:**
- Wherever the ledger exposes "is this transaction still live" for a single id, reuse it rather
  than re-deriving; add a batched sibling ("of these transaction ids, which are voided") for the
  list/grid cases rather than N+1 calls or joining transaction liveness into the receipt list
  query itself — follow the shape of whatever the existing Merchant-column display-map lookup
  uses, since it solves the identical "batch a cross-module fact onto a list of receipts" problem.
- The two places that void the receipt's existing linked transaction (re-entry-on-confirm; the
  committed-delete dialog's void axis) need to treat "target already voided" as success, not
  propagate the ledger's "not found or not live" failure.
- The receipt-state badge/dot rendering needs a third visual case beyond its existing per-state
  styling: "committed, transaction voided" — grey, labelled/titled "Void" — on top of (not
  instead of) the existing per-`receipt.state()` styling mechanism.
- The State filter's option set gains one more selectable value whose semantics are a compound
  filter (`state = committed` AND transaction voided), not a literal `receipt.state`.

**Acceptance criteria:**
- [ ] Committing a receipt, then voiding its transaction from the register, followed by Reopen →
      edit → Confirm on that receipt succeeds and re-books a fresh transaction (the previously
      already-voided one is left alone, not double-voided, no error).
- [ ] The same scenario, followed by the 5-way committed-delete dialog with "void transaction"
      selected, completes without error.
- [ ] The single-receipt pane for that receipt, before any recovery action, shows no "Edit
      transaction" link and instead shows an explanatory note naming the void and pointing at
      Reopen.
- [ ] The single-receipt pane for an ordinary `committed` receipt (transaction still live) is
      unchanged — link still shown, no note.
- [ ] The PC list shows a grey "Void" badge for that receipt instead of the ordinary "Committed"
      badge, and an ordinary live-transaction `committed` receipt is unaffected.
- [ ] The PC list's State filter has a new selectable option that returns exactly the
      committed-and-voided receipts, and no others.
- [ ] The mobile browse grid shows a grey "Void" state dot for that receipt instead of the
      ordinary committed dot.
- [ ] None of the above issues an extra query per row — list and grid renders use one batched
      lookup regardless of how many committed receipts are visible.

**Out of scope:**
- Any change to voiding a transaction from the register itself (no new warning, confirmation, or
  awareness of linked receipts added there).
- The 5-way committed-delete dialog's presentation/copy (e.g. labelling its void axis as "already
  voided") — considered and deliberately deferred, not part of this fix.
- A new `receipt` state, lifecycle value, or stored/persisted flag of any kind for this — it must
  stay a live-derived display fact.
- Composable filtering (e.g. combining the new voided filter with other criteria beyond the
  existing State/Range filters) — the single new dropdown option is the full ask.

> *This was generated by AI during triage.*
