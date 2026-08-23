# Two entry architectures where one would do — make a simple transaction a split with one line

Status: needs-triage
Severity: medium
Area: Transaction register — entry/edit architecture (`operations`); receipts editor rides on the same model

> *Investigation only — no code was changed. Owner asked whether the simple/split split can be
> collapsed; this records what the code actually shows and what it would cost.*

## The owner's question

> "I understand we have an architectural difference between simple transactions and split
> transactions. I don't like it. Would it be possible to refactor the code and the UI so that the
> 'simple' transaction would be nothing else than a 'split' with only one item?"

## Finding: the split model is already the general one

Every shape the simple dock supports is expressible as a one-line split. Checked case by case
against `DockEntry` and `SplitEntry`:

| Simple dock (`DockEntry`) | One-line split (`SplitEntry`) | Verdict |
|---|---|---|
| category counterpart | line with `categoryId` | same |
| transfer (`transferDirection`) | line with `transferDirection` | same |
| person counterpart (`personName`/`personDirection`) | line with the same fields | same |
| funding person (`for`/`by` in the Account field) | `fundingPersonName` — already on `SplitEntry` | same |
| `categoryCurrencyCode` (leaf-currency override) | `spendingCurrencyCode` (header) | same concept, renamed |
| `amount` / `categoryAmount` / `baseAmount` | line amount / `fundingTotal` / `baseTotal` | numerically identical at one line |
| cross-currency transfer | cross-currency split whose one line targets an account in the spending currency | expressible (`DockSplitService.resolveLine` requires exactly that) |

Nothing the dock can express is lost. The header `total` is only the `remaining` reference and is
optional (`status = none` when blank), so a one-line same-currency split still needs exactly **one**
amount typed — the dock's defining ergonomic is not structurally threatened.

### Three independent signals that the dock is the outlier

1. **The two `commit` methods are the same five steps** — resolve funding account, build legs,
   resolve payee, build `TransactionDraft`, record-or-edit — differing only in leg construction.
   `resolveFundingAccount` is duplicated near-verbatim; `DockSplitService`'s own javadoc says
   *"auto-provisioned here at commit exactly as `DockCommitService#resolveFundingAccount` does for
   the simple dock."*
2. **The receipt editor is already a third entry surface built on the split model**
   (`ReceiptSplitEntries` → `SplitEntry` → `DockSplitService`). Two of three surfaces already speak
   split; the dock is the one that doesn't.
3. **Edit dispatch is exception-driven.** `RegisterEntryController.edit` tries `DockEditService`,
   catches `IllegalArgumentException`, tries `SplitEditService`, catches again, then gives up. That
   control flow is the design saying the split is the fallback shape for everything.

## Three real obstacles

**1. The sign inconsistency — `transaction-register-ui/06`, still open.** `−20` against an expense
books **−20 (outflow)** in the dock and **+20 (a refund)** in the split panel. Unification cannot
sidestep it: one model must win. This is an argument *for* the refactor (it forces the settlement
the owner already wants) but it means this work carries that decision. Issue 06's trap applies in
full: each surface's edit reconstruction is written against its own commit path
(`DockEditService.amountText` ↔ `signedAmount`; `SplitLineAmounts.amountText` ↔
`signedContribution`), so the commit half and the reconstruct half must move together or re-saving
an untouched transaction silently flips a sign.

**2. Tag attachment differs — and the dock is the one that matches the ratified rule.**
data-model §10.2 (owner-confirmed 2026-07-14) says a transaction-level tag expands to one
`posting_tag` row per leg — **every** leg. `DockCommitService` does that; `DockSplitService` puts
them on the **funding leg only**. Unifying on the split's behaviour would silently change documented
behaviour for every simple transaction; unifying on the dock's rule makes header tags land on
category legs too, needing dedup against per-line tags. A domain decision, not a mechanical merge.

**3. The dock's UX is not the split panel's.** One row, keyboard-first, `stickyAfterCommit`
re-arming for the next entry. Nothing forces that to change — but it is a separate concern from the
domain model and should be kept separate deliberately.

## Two levels of unification

**Level 1 — unify the domain layer, keep two renderings (recommended).**
`DockEntry` becomes a factory for a one-line `SplitEntry`; `DockCommitService.commit` delegates;
`DockEditService` folds into `SplitEditService` reconstructing 2..n legs, which removes the
try/catch dispatch. **The dock template is untouched** — same keyboard flow, same sticky behaviour;
it simply posts a form that maps to one line. This is where essentially all the duplication lives
(`DockCommitService` 375 lines and `DockEditService` 361 lines largely stop existing) and it forces
exactly one decision: the sign model.

**Level 2 — unify the UI too**, so the dock is literally a split panel rendered with one line and no
add-line affordance. Larger, riskier, and it puts the most-used entry flow on the line to buy
nothing structural that Level 1 has not already bought. Not recommended, and certainly not bundled
with Level 1.

## What it would dissolve

- **`transaction-register-ui/20`** ("editing a simple transaction can't turn it into a split") stops
  being a feature to build: with one model there is no conversion to perform, only a line to add.
- **`transaction-register-ui/06`** is settled as a precondition rather than left open.
- The exception-driven edit dispatch disappears.

## Cost and sequencing

The test suites are the honest measure: `DockCommitServiceTest` 927 lines, `DockEditServiceTest`
498, `SplitPanelAssemblerTest` 423, `RegisterEntryScreenIntegrationTest` 1417,
`RegisterSplitScreenIntegrationTest` 1182. Most survive as behaviour tests against a delegating
dock, but every one is touched, and any asserting tag-on-every-leg or an explicit signed amount
legitimately changes meaning. This is a stage, not an afternoon.

**Sequence after `receipt-processing/23`** (cross-currency receipt commit). That issue adds a caller
and a promoted service around `DockSplitService` and would collide head-on with this refactor; doing
it first also proves the split model carries all three surfaces before the dock is folded into it.

## Comments

Filed 2026-08-23 from the owner's architectural question, after reading both paths. Supersedes
nothing on its own — but it subsumes issue 06 (which must be settled to do this) and issue 20
(which stops existing if it is done).
