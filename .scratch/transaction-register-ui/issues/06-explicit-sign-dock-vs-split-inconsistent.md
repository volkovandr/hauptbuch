# An explicit +/- on the amount means opposite things in the dock and in the split panel

Status: needs-triage
Severity: high
Area: Transaction register — entry (§3.8 sign resolution)

The two entry surfaces implement **different models** for what a leading `+`/`−` on the amount
means. The same typed string books the opposite sign depending on which surface was used. Found
while implementing stage 8b.1's sigil-vs-category-type check, which cannot be written until this
is settled — the check has to know what direction the amount actually implies.

## The two models

**Simple dock — `DockCommitService.signedAmount` — ABSOLUTE.** The explicit sign names a direction
outright, regardless of the counterpart: `+` debits the funding leg (funds enter), `−` credits it
(funds leave). This is what register §3.8's prose documents: *"− = funds leave the account, + =
funds enter it."*

**Split panel — `SplitLineAmounts.signedContribution` / `transferContribution` — FLIP.** The typed
sign is kept, then negated for an expense (or for a `To →` transfer). So the explicit sign inverts
whatever the counterpart's default was. This is what register §3.5's stage-8b sigil table reads as:
*"the category type sets the funding leg's sign; a negative amount flips it."*

Traced:

| Typed | Counterpart | Dock books | Split panel books |
|---|---|---|---|
| `20`   | expense | −20 (outflow) | −20 (outflow) — agree |
| `−20`  | expense | −20 (outflow, sign is a no-op) | **+20 (inflow, a refund)** |
| `+20`  | expense | **+20 (inflow, a refund)** | −20 (outflow, sign is a no-op) |
| `100`  | `To →`  | −100 | −100 — agree |
| `−100` | `To →`  | −100 | **+100** |

## Where it does and does not bite

The two models are **bit-identical whenever the counterpart's default is an inflow** — income
categories, `From ←` transfers, and `by` person counterparts. They differ **only when the default is
an outflow**: expense categories, `To →` transfers, and `for` person counterparts.

Bare amounts carrying no sign — the ≥95% path — are identical under both models, so ordinary entry
is unaffected. Neither model loses expressiveness; they merely swap which key expresses a reversal
(refund/storno): `+` in the dock, `−` in the panel.

Stored postings are not affected by the choice — they hold signed amounts and nothing re-derives a
booked sign from typed text.

## The hazard to be careful about when fixing

Each surface's **edit reconstruction** is written to match its own commit path:
`DockEditService.amountText` pairs with `signedAmount`; `SplitLineAmounts.amountText` pairs with
`signedContribution`. Each pair is self-consistent today, so round-tripping works *within* a
surface. Any fix must move the commit half and the reconstruct half **together**, or re-saving an
untouched edited transaction will silently flip a sign — a quiet money bug.

Worth checking before changing either side: whether any already-entered transaction used an
explicit sign on an expense or a `To →` transfer, since those are the rows that would reconstruct
differently in edit mode after a change.

## Also to settle

The docs contradict themselves and should be fixed alongside the code, so the two stop drifting:

- register §3.8's prose states the absolute rule;
- register §3.5's stage-8b sigil table (row 5: `for Max` | expense | `−` | ✅) assumes the flip rule.

Under the absolute rule, that row's Amount cell would have to be `+`. Under the flip rule, all six
rows are literal as written.

The owner's call (2026-07-20): document and defer — the behaviour is inconsistent, and it should be
re-thought and tested thoroughly rather than settled in passing during 8b.1.

## A second case the same deferred check would have caught

Surfaced by the 8b.1 spec review, 2026-07-20. Deferring the check leaves a **sigil-vs-sigil**
contradiction committing silently, which register §3.5 explicitly forbids ("never silently
corrected: flipping the sign of money on the user's behalf is how books go quietly wrong").

`DockCommitService` reads `fundingPersonDirection` only as a *presence flag* — it decides "the
funding leg is a person", never a direction. The funding leg's sign always comes from the
counterpart (category type, transfer direction, or the counterpart person's sigil). So with a person
on **both** sides:

- Account `for Max` + Category `by Anna` → consistent (Max debit, Anna credit) — commits correctly.
- Account `for Max` + Category `for Anna` → **both sigils assert the debit side**, which two legs
  summing to zero cannot satisfy. The counterpart wins; `for Max` is silently violated.
- Account `by Max` + Category `by Anna` → the mirror image, same silent violation.

Note this sub-case is **independent of the absolute-vs-flip question above**: `for X` + `for Y` can
never be satisfiable under either sign model, because whichever way the amount is signed exactly one
of the two assertions is violated. It could therefore be checked before the sign semantics are
settled — but it belongs to the same "sigil is a checked assertion" feature, so it is parked here
rather than half-built.

The same applies to a sigil against a transfer keyword (Account `for Max` + Category `To → Cash`),
though there the amount's explicit sign does re-enter, so that one genuinely waits on the decision
above.

## Comments
