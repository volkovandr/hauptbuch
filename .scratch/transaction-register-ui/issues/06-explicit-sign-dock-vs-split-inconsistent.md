# An explicit +/- on the amount means opposite things in the dock and in the split panel

Status: ready-for-agent
Category: bug
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

## How it should work ideally

1. The categories/accounts on the right side should determine the direction of the transfer
2. When there is no sign, the transaction goes according to that category/right-side account direction
3. When the sign is positive, nothing changes, the transaction goes according to the category/right-side account direction
4. When the sign is negative, the transaction goes in the opposite direction
5. The prefix "by/for" on the left side is meaningless and is ignored, only the prefix on the right sign matters
6. Rules:

Behavior on no sign, or positive sign:

| Account on the left side | Category or account on the right side | Result            | Meaning                                                             |
|--------------------------|---------------------------------------|-------------------|---------------------------------------------------------------------|
| regular A (Cash)         | expense B (Sweets)                    | credit A, debit B | I bought Sweets, paying with Cash                                   |
| regular A (Cash)         | income B (Salary)                     | debit A, credit B | I received Salary in Cash                                           |
| regular A (Cash)         | To → B (Savings)                      | credit A, debit B | I transferred Cash to my Savings account                            |
| regular A (Cash)         | From ← B (Savings)                    | debit A, credit B | I withdraw Cash from my Savings account                             |
| regular A (Cash)         | for B (Anna)                          | credit A, debit B | I gave money to Anna                                                |
| regular A (Cash)         | by B (Anna)                           | debit A, credit B | Anna gave me money                                                  |
| by A / for A (Anna)      | expense B (Sweets)                    | credit A, debit B | Anna buys Sweets for me                                             |
| by A / for A (Anna)      | income B (Salary)                     | debit A, credit B | Anna received my Salary                                             |
| by A / for A (Anna)      | To → B (Cash)                         | credit A, debit B | Anna gives me money                                                 |
| by A / for A (Anna)      | From ← B (Cash)                       | debit A, credit B | Anna receives money from me                                         |
| by A / for A (Anna)      | for B (Bob)                           | credit A, debit B | Anna paid money for Bob. Now Anna owes me less, but Bob owes me more |
| by A / for A (Anna)      | by B (Bob)                            | debit A, credit B | Anna gets payed by Bob. Now Anna owes me more, but Bob owes me less |

Behavior on negative sign:

| Account on the left side | Category or account on the right side | Result              | Meaning                                                               |
|--------------------------|---------------------------------------|---------------------|-----------------------------------------------------------------------|
| regular A (Cash)         | expense B (Sweets)                    | debit A, credit B   | I returned Sweets, received back Cash                                 |
| regular A (Cash)         | income B (Salary)                     | credit A, debit B   | I returned back my Salary , paying with Cash                          |
| regular A (Cash)         | To → B (Savings)                      | debit A, credit B   | I storned the transaction of transferring Cash to my Savings account  |
| regular A (Cash)         | From ← B (Savings)                    | credit A, debit B   | I storned the transaction of withdrawing Cash from my Savings account |
| regular A (Cash)         | for B (Anna)                          | debit A, credit B   | I stored the transsaction of giving money to Anna                     |
| regular A (Cash)         | by B (Anna)                           | credit A, debit B   | Storno of the transaction when Anna gave me money                     |
| by A / for A (Anna)      | expense B (Sweets)                    | debit A, credit B   | Anna returned Sweets that she had bought for me and kept the money    |
| by A / for A (Anna)      | income B (Salary)                     | credit A, debit B   | Storno of the transaction when Anna received my Salary                |
| by A / for A (Anna)      | To → B (Cash)                         | debit A, credit B   | Anna takes back the money she had given me by mistake                 |
| by A / for A (Anna)      | From ← B (Cash)                       | credit A, debit B   | Anna returned back the money she had received from me by mistake      |
| by A / for A (Anna)      | for B (Bob)                           | debit A, credit B   | Bob returns Anna the money she had paid money for him by mistake      |
| by A / for A (Anna)      | by B (Bob)                            | credit A, debit B   | Anna returns Bob the money she got payed by him by mistake            |

"To ->" and "From <-" are not possible on the left side, this is good so.
The cases when "by/for" on the left side are combined with "To ->" or "From <-" on the right side with negative sign are possible, but are not likely to be used because it is
much more explicit and understandable to use the proper direction on the right side and use no sign.

## Comments

See also `transaction-register-ui/21` (unify the simple and split entry models): that refactor
cannot proceed until this is settled, and settling it is listed there as a precondition.

---

> *This was generated by AI during triage (2026-09-09), from the owner's matrix above.*

## Agent Brief

**Category:** bug
**Summary:** Adopt the split panel's FLIP sign model in the dock, make the funding-leg sigil a
checked assertion against the *net* funding leg, and fold the owner's matrix into the docs so §3.5
and §3.8 stop contradicting each other.

**Current behavior:**

The dock is ABSOLUTE (`DockCommitService.signedAmount`, paired with `DockEditService.amountText`);
the split panel is FLIP (`SplitLineAmounts.signedContribution`/`transferContribution`/
`personContribution`, paired with `SplitLineAmounts.amountText`). The same typed string books
opposite signs whenever the counterpart's default is an outflow — expense categories, `To →`
transfers, `for` person counterparts. Bare amounts (the ≥95% path) are identical under both.

Both `DockCommitService` and `DockSplitService` read `fundingPersonDirection` only as a *presence
flag* ("the funding leg is a person"), so the left sigil's asserted direction is discarded and a
contradiction commits silently — which register §3.5 explicitly forbids.

**Desired behavior:**

*1 — FLIP everywhere (the matrix's rules 1–4).* The counterpart determines direction. No sign or a
leading `+` books that direction; a leading `−` books the opposite. The dock adopts what the split
panel already does; the split panel does not change.

*2 — The funding-leg sigil is a checked assertion, verified against the net.* Not a table of sigil
pairs — one comparison:

```
if the funding leg is a person:
    expected = (for → debit/+,  by → credit/−)
    actual   = sign(funding leg amount)        // after summing every line
    if actual ≠ 0 and actual ≠ expected → refuse with an explanation
```

The right side *declares* each leg's direction by construction, so right-side sigils need no check;
only the derived funding leg can disagree. A net of exactly zero is unverifiable and commits.

This is a generalisation, not a new semantic: it reproduces all six rows of §3.5's ratified sigil
table exactly, and extends them to N lines. It also settles the "second case" above — `for X` +
`for Y` is refused because the net disagrees, while the owner's Anna/Bob split (`by Anna` funding;
`Sweets 150` + `by Bob 50` → net −100, credit) *agrees* and commits. `by`→`by` is therefore legal
or illegal depending on the net, which a pairwise enumeration would get wrong.

*3 — Where it fires.* Blocking happens at commit, in both surfaces: in a split the net is not known
until every line is entered, so an early check would flash a contradiction on line 1 and clear it on
line 2. The split panel additionally shows the **resolved funding direction** live beside
`remaining`, as a passive readout — never an error mid-entry.

*4 — Docs.* The owner's 24-row matrix plus the rule above replace §3.8's "Explicit sign overrides"
paragraph and reconcile §3.5. §3.5's six-row table stands as written (it is literal under FLIP);
its "checked assertion" paragraph gains the net-sign clarification.

**Key interfaces:**

- `DockCommitService.signedAmount` — change explicit-sign handling from absolute (`+` = inflow, `−`
  = outflow) to negate-the-default (`−` negates, `+`/bare do not). Keep the `defaultOutflow`
  parameter; only the branch changes.
- `DockEditService.amountText` — **must move in the same commit.** It is derived from the stored
  posting, so `reconstruct → text → commit` round-trips under either model, but only if both halves
  move together. There is no data migration: no stored row changes and nothing re-derives a booked
  sign from typed text.
- `SplitLineAmounts.*` — unchanged. It is the reference implementation of the target semantics.
- The new assertion check needs the funding leg's net sign, already computed at every site:
  `DockCommitService.commit` (`fundingAmount`), `DockSplitService.sameCurrencyLegs`
  (`fundingAmount`) and `crossCurrencyLegs` (`fundingSign`).
- `SplitPanelAssembler` — the live funding-direction readout; reuse the `lenientContribution` sums
  it already computes. `remaining` is untouched.
- The receipt confirm path books through `DockSplitService` and can carry a `fundingPersonName`, so
  it inherits both the model and the check with no receipts-side change.

**Acceptance criteria:**

- [ ] `20`, `+20`, `−20` against an expense category book identically in the dock and the split
      panel (outflow, outflow, inflow); same for `To →` transfers and `for` person counterparts.
- [ ] All 24 rows of the owner's matrix are covered by tests, both surfaces.
- [ ] Editing and re-saving an untouched transaction reproduces byte-identical postings, for every
      shape, on both surfaces (the round-trip guard).
- [ ] `for Max` funding + `for Anna` counterpart is refused with an explanation; `for Max` + `by
      Anna` commits.
- [ ] `by Anna` funding + (`Sweets 150`, `by Bob 50`) commits and books `Anna −100, Bob −50,
      Sweets +150`; entering the same receipt as `by Bob` funding + (`Sweets 150`, `by Anna 100`)
      books the identical three postings.
- [ ] `by Anna` funding + `by Bob 50` alone is refused.
- [ ] A funding leg netting to exactly zero commits with either sigil.
- [ ] The split panel shows the resolved funding direction live; `remaining` behaves exactly as
      before.
- [ ] §3.5's six-row table still holds row for row against the implementation.
- [ ] `ui-transaction-register.md` §3.8 and §3.5 updated; the matrix lives in the doc, not only here.
- [ ] `./gradlew check` green. Tiers per §6: the sign functions and the assertion check are pure
      decision logic → **unit**; the refusal rendering as an htmx error → **integrationTest**
      controller acceptance. No SQL is involved, so nothing lands in `sqlLogicTest`.

**Out of scope:**

- `transaction-register-ui/21` (unifying the simple and split entry models) — settling the
  semantics first makes that refactor mechanical; it is not a precondition for this.
- `transaction-register-ui/25` (picker leaves/hierarchy).
- Making `+` mean anything other than "redundant, same as bare". Note the silent habit change:
  `+20` on an expense books a refund today and an ordinary outflow afterwards.

---

## Implementation note (2026-09-09) — finished, awaiting owner confirmation

Branch `doc/triage-sign-model-and-zero-receipt` (not yet merged). `./gradlew check` green.

- **FLIP everywhere.** `DockCommitService.signedAmount` now negates the counterpart's default only
  on a leading `−`; `+`/bare are identical. `DockEditService.amountText` moved in the same commit
  (bare for the default direction, leading `−` for the flip, never `+`); round-trip guarded by unit
  tests. `SplitLineAmounts.*` unchanged — it was already the reference.
- **Funding-leg sigil = checked assertion.** New `FundingSigilCheck.verify(direction, net)`, called
  by `DockCommitService.commit` and both `DockSplitService` leg builders against the funding leg's
  net signed amount. `for` ⇒ debit, `by` ⇒ credit; net of exactly zero commits; disagreement throws
  `IllegalArgumentException`, which both controllers already render as an inline dock/panel error.
  Replaces the presence-only `fundingPersonDirection` handling.
- **Docs.** `ui-transaction-register.md` §3.8 rewritten (flip rule + the owner's direction matrix as
  a 12-row table with the "leading `−` inverts every row" note), §3.5's checked-assertion paragraph
  gains the net-sign clarification, changelog v0.6. §3.5's six-row table is literal under FLIP,
  unchanged.
- **Tests.** `FundingSigilCheckTest` (new); flip-model, refund, person-on-both-sides, mirror,
  net-zero, and by→by-refused cases across `DockCommitServiceTest` / `DockSplitServiceTest` /
  `DockEditServiceTest`; htmx-error acceptance in `RegisterEntryScreenIntegrationTest` and
  `RegisterSplitScreenIntegrationTest`. One pre-existing split test that used a contradictory
  `for`+expense funding combo switched to `by`.
- **Not done:** a literal 24-row matrix test — several of the owner's raw p2p rows are refused by
  the checked assertion (the doc now says so), so a verbatim table test would encode superseded
  behaviour. Coverage is by representative case instead.
