# A receipt in a currency other than the paying account's cannot be booked

Status: resolved
Category: enhancement
Severity: high
Area: Receipts — post-process editor + Confirm gate; register split panel; `ledger` rate prefill

## Report (owner)

A receipt in a currency that differs from the paying account's cannot be confirmed. The editor warns
about it and Confirm refuses outright. Real case, not hypothetical — this is how a foreign-currency
purchase on a home-currency card arrives.

## What exists today

- `ReceiptEditorAssembler.currencyMismatch` flags the divergence; `receipt-process.html` prints
  *"The chosen currency differs from the paying account's — you can still save the draft."*
- `ReceiptConfirmGate.checkCurrency` **hard-blocks** it: *"Booking a cross-currency receipt is not
  implemented yet — change the currency or the paying account."* Deferred to plan §14 during 9g.
- `ReceiptEditor`'s javadoc: *"Single-currency only in this slice"*; the assembler always builds
  `new SplitCurrency(false, …)` and the template hardcodes `data-split-cross="false"`.

## What already works and must be reused

- **The commit path is done and needs no change.** `SplitEntry` already carries
  `spendingCurrencyCode`, `fundingTotal`, `baseTotal`, and `DockSplitService.crossCurrencyLegs`
  books it (register §3.8a/§3.10, stage 7d.2): funding leg pinned to the header totals, category
  legs in the spending currency with base allocated proportionally and the last line absorbing the
  residual so `Σ base_amount = 0` holds exactly. `resolveLine` already routes category lines to the
  spending-currency leaf, provisions a person's debt leaf in the spending currency, and rejects a
  transfer line targeting an account in another currency with a clear message.
  `ReceiptSplitEntries.of` simply passes `null, null, null` for all three.
- **The per-line chrome is already currency-generic.** `fragments/line-editor.html` relabels the
  amount input `Amount (USD)` and renders the read-only `≈ 66,67 CHF · 63,33 EUR` derived span
  whenever `currency.crossCurrency()` — and `receipt-process.html` already passes
  `currency=${editor.currency()}` into that same fragment. **The fragment takes no change.**
- **The header chrome exists** in `fragments/split-panel.html` (funding-total field, base-total
  field gated on `neitherIsBase`, per-currency remaining readouts) — but as part of the register's
  panel, not as a reusable fragment.
- `SplitPanelAssembler.resolveCurrencyContext` computes the whole header state, but it and its
  `CurrencyContext` record are **private**; `DockAmountFieldsService.splitBaseTotalPrefill` is
  package-private. `receipts` can reach none of it. `SplitCurrency` and
  `ledger.CrossCurrencyFieldsService` are public.

## Grilled decisions (2026-08-23 — do not re-litigate)

1. **The funding total is proposed, not demanded.** At review time the operator does not know what
   actually came off the account — the card's real charge, with the bank's markup, is on a statement
   that has not arrived. So `Off account (CHF)` is pre-filled from `rate_as_of` and is overtypeable.
   Booking an estimate is accepted.
2. **Both totals persist across Save** — migration **V17** adds `receipt.funding_total` and
   `receipt.base_total` (`numeric(19,4)`), joining the existing header columns. An overtyped funding
   total must survive Save→reopen, and the base total stays an operator-confirmed frozen number
   (data-model §6.4 treats a non-null `base_amount` as a frozen fact, not a derived one).
3. **A new `/receipts/{id}/lines/currency` endpoint** re-renders `#receipt-editor`, mirroring
   `/register/split/currency`.
4. **The header-state rule is promoted to a public `operations` service** returning `SplitCurrency`.
   `SplitPanelAssembler` is refactored onto it, so exactly ONE implementation of the rule exists.
5. **The prefill itself lives in `ledger.CrossCurrencyFieldsService`**, as a sibling of
   `prefillBase` — the register's existing home for prefill logic. Rates are stored only against
   base (`units of BASE per 1 unit of currency_code`), so proposing a funding total from a spending
   total goes spending→base→funding *inside that method*. The two-currency case (paying account in
   base currency) collapses to a single lookup.
6. **One prefill rule on both surfaces:** any blank total is proposed from the rate feed, in
   whichever direction the filled siblings allow — funding from spending, base from funding. A total
   the operator typed is **never** overwritten. Accepted consequence: a proposal can go stale if the
   number it was derived from is edited afterwards (already true of the base prefill today).
7. **Recompute fires on any header field that feeds a proposal** — account, spending currency, and
   the three totals — on **both** surfaces. Without the totals as triggers the register's new
   funding proposal would never fire, since the currency is normally picked before anything is typed.
8. **The Confirm gate validates instead of refusing**, every finding in plain English, covering all
   four things `DockSplitService` would otherwise throw raw on.
9. **The mismatch warning and `ReceiptEditor.currencyMismatch` are removed.** The fields appearing
   are the signal, exactly as in the register — a warning on a fully supported mode trains the
   operator to ignore warnings.
10. **Nothing marks a transaction as holding an estimate.** It is an ordinary cross-currency
    transaction: correctable by editing it in the register (stage 7f already covers editing
    cross-currency shapes), and statement reconciliation (stage 13) is where the real charge meets
    it. No new column, no badge, no new concept.
11. **Scope stops at the split panel and the receipt editor.** The simple dock keeps its base-only
    prefill; extending it is a separate follow-up.

## Agent Brief

**Category:** enhancement
**Summary:** Let a receipt billed in one currency be booked against a paying account in another, by
giving the receipt editor the register's cross-currency header — with the funding total proposed
from the rate feed — and turning the Confirm gate's refusal into a validation.

**Current behavior:**
The post-process editor is single-currency by construction. Picking a paying account whose currency
differs from the receipt's produces a Save-time warning and an absolute Confirm block. The editor's
`SplitCurrency` is always the single-currency shape, so no funding/base total fields render, no
per-line derived columns render, and `ReceiptSplitEntries` hands the commit path no spending
currency or header totals.

**Desired behavior:**
Choosing a paying account (or header currency) that makes the receipt cross-currency reveals the
same header the register's split panel shows — `Off account (CUR)` and, when neither leg is the
book's base, `Base (CUR)` — plus a remaining readout per currency in play. Every line's amount input
stays editable **in the receipt's currency** and gains the read-only `≈ … CHF · … EUR` equivalents
beside it. The funding total arrives pre-filled from the carry-forward rate and is overtypeable.
Confirm books the transaction through the existing cross-currency split path.

**Key interfaces:**
- `ledger.CrossCurrencyFieldsService` — **new sibling of `prefillBase`** proposing a funding-currency
  total from a spending-currency total, triangulating through base internally (`rateAsOf` is only
  ever base-per-unit). Returns blank when either leg has no stored rate on or before the date —
  never invent a rate. Follow `prefillBase`'s lenient contract: never throw on blank or malformed
  input.
- **New public service in `operations`' root package** resolving the cross-currency header state
  (`cross?`, the three currency codes, `neitherIsBase`, the two derived rates, both totals, both
  remaining readouts) and returning `SplitCurrency`. Both `SplitPanelAssembler` and
  `ReceiptEditorAssembler` call it. This is what lets `receipts` have the behaviour without reaching
  into `operations` internals (CLAUDE.md §1.1) — `resolveCurrencyContext`/`CurrencyContext` are
  private today and must move rather than be duplicated. It must also expose the per-line derived
  amounts, which `SplitPanelAssembler` currently computes through `CurrencyContext.derived`.
- `ReceiptEditorForm` — gains `fundingTotal` and `baseTotal` (bound as `String`, like every other
  field here, and re-emitted so a round-trip preserves them).
- `ReceiptHeaderDraft` + `ReceiptRepository.saveEditorHeader` — gain the two persisted totals.
- `ReceiptProcessingController` — gains the `/receipts/{id}/lines/currency` mapping alongside its
  existing `lines/add-line`, `lines/remove-line`, `lines/redistribute` and `lines/save`.
- `ReceiptConfirmGate.checkCurrency` — becomes the cross-currency validation described below.
- `ReceiptSplitEntries.of` — passes the spending currency and the two totals instead of
  `null, null, null`. Set `spendingCurrencyCode` only when it actually differs from the paying
  account's currency, so a single-currency receipt keeps booking through the untouched path.
- `DockSplitService`, `SplitEntry`, `fragments/line-editor.html` — **used as-is, not modified.**

**Migration (V17):**
`receipt.funding_total numeric(19,4)` and `receipt.base_total numeric(19,4)`, both nullable, both
NULL for a single-currency receipt.

**The Confirm gate's four cross-currency checks (replacing the refusal):**
- the funding total is present and non-zero;
- the base total is present and non-zero **when neither leg is the base currency**;
- a base currency is set in settings (`DockSplitService` throws `IllegalStateException` otherwise);
- every transfer line targets an account in the **spending** currency (`resolveLine` throws
  `IllegalArgumentException` otherwise — its message is a good model for the wording).
Each is a plain-English hard block joining the gate's existing list, so the operator never lands on
an error page mid-Confirm.

**Template work in `receipt-process.html`:**
- The funding-total and base-total fields in the header, gated on `crossCurrency` /`neitherIsBase`,
  matching `split-panel.html`'s labels and placeholders.
- The per-currency remaining readouts in the status bar.
- `data-split-cross`, `data-split-rate-funding`, `data-split-rate-base` are **hardcoded to
  `"false"`/`"0"`** today — they must render from the resolved `SplitCurrency`, or `keyboard.js`
  will not refresh the derived cells as the operator types.
- `hx-post` to the new currency endpoint on `change` of the Account select, the Currency picker, and
  the three total inputs.
- **Delete** the `currencyMismatch` warning paragraph.

**Register-side work (decision 6/7):**
- `/register/split/currency` gains the funding-total proposal alongside its existing base one.
- The split panel's three total inputs gain the same `change` trigger, so a proposal fires when the
  field it derives from is filled.
- Behaviour when both totals are already typed is unchanged — a filled field is never overwritten.

**Also fold in:**
- `ReceiptEditorAssembler.lineView` currently passes `""`,`""` for `SplitLineView`'s
  `accountAmount`/`baseAmount`; it must supply the derived per-line equivalents or the read-only
  cells render blank.
- `receipt_line.amount`'s comment in V12 reads *"native currency of the paying account"*, which
  stops being true — lines are in the receipt's (spending) currency. Migrations are forward-only, so
  correct this where the meaning is documented in code/docs rather than editing the applied file.
- `data-model.md` §13.1 (the two new `receipt` columns) and the plan's §14 entry (this stops being
  backlogged) both need updating. Keep it to the minimum the docs discipline (CLAUDE.md §8a) allows.

**Acceptance criteria:**
- [ ] Picking a paying account in another currency than the receipt reveals `Off account (CUR)`,
      pre-filled from `rate_as_of`, without a Save.
- [ ] `Base (CUR)` appears only when neither the funding nor the spending currency is the book's
      base, and is pre-filled from the funding total exactly as the register's is.
- [ ] With no stored rate for a leg on or before the receipt date, the field renders blank rather
      than a guessed number, and Confirm blocks until it is filled.
- [ ] Each line keeps an editable amount in the receipt's currency and shows read-only equivalents
      in the account's currency and (when shown) the base currency.
- [ ] Editing a total updates the remaining readouts; a total the operator typed is never
      overwritten by a later proposal.
- [ ] Save persists both totals; reopening the `processed` receipt shows the overtyped values, not
      a fresh proposal.
- [ ] Confirm books one transaction whose funding leg carries the funding total natively and the
      base total as `base_amount`, whose category legs are in the spending currency, and whose
      postings satisfy `Σ base_amount = 0` exactly.
- [ ] Confirm blocks in plain English on each of the four cross-currency checks, with the receipt
      left untouched.
- [ ] A single-currency receipt is byte-for-byte unchanged: no new fields, no `spendingCurrencyCode`
      on the split entry, `funding_total`/`base_total` NULL.
- [ ] The register's split panel still books cross-currency splits exactly as before, and now
      proposes a blank funding total.
- [ ] `ApplicationModules.verify()` is green — `receipts` reaches `operations` only through public
      root-package types.
- [ ] `./gradlew check` is fully green, with no tool weakened and no suppression added.

**Test tiers (CLAUDE.md §6):**
- **unit** — the new `operations` header-state service (cross vs. single, `neitherIsBase`, derived
  rates, remaining readouts); `ReceiptConfirmGate`'s four checks; `ReceiptSplitEntries` passing the
  currency and totals through (and *not* passing them for a single-currency receipt);
  `ReceiptEditorAssembler`'s derived per-line amounts. The prefill's triangulation belongs here too,
  with `ExchangeRateService` mocked, including the missing-rate and malformed-input paths.
- **integrationTest** — V17 applies on a fresh container; `saveEditorHeader` round-trips the two new
  columns; controller acceptance for the new currency endpoint (mismatched account ⇒ the funding
  field renders pre-filled) and for Confirm booking the expected postings against real Postgres.
- **sqlLogicTest** — nothing new; no query logic is added.

**Out of scope:**
- Extending the prefill to the simple entry dock (decision 11).
- Any marker distinguishing an estimated funding total from a statement-confirmed one (decision 10).
- Statement reconciliation correcting a booked estimate — that is stage 13.
- Receipts spanning more than two currencies. One receipt is one merchant billing one currency; a
  transfer line in a third currency stays an error, as it is in the register.

## Comments

Filed 2026-08-23 on the owner's report; grilled the same day. The decisive finding of the grilling is
that the ledger work is already done — this issue is an entry-surface gap, not an engine gap.

During the grilling the owner rejected a proposed asymmetry between the two surfaces ("the register
already shows the amount in the transaction's currency, the account's currency and the base
currency, and any of them can be altered"). That is correct, and it collapsed an earlier draft in
which only receipts gained the funding proposal: the surfaces differ only in *which* number is known
first, not in how the totals behave, so decision 6 makes the prefill rule one rule for both.

Resolved 2026-08-25 on branch `issue/receipts-23-cross-currency-commit` (eee63c8). All eleven
grilled decisions implemented as written; nothing re-litigated.

The shape that fell out: the header-state rule moved from `SplitPanelAssembler`'s private
`CurrencyContext` into a public `operations.SplitCurrencyService` (+ `SplitCurrencyContext`,
`SplitCurrencyQuery`, `SplitTotals`, `SplitTotalsQuery`), which both assemblers now read — one
implementation, as decision 4 demanded. `ledger.CrossCurrencyFieldsService` gained
`prefillFundingTotal` beside `prefillBase`, triangulating spending → base → funding because rates
are stored only against base; `SplitCurrencyService.proposeTotals` chains the two so a blank
funding total is proposed from the spending total and a blank base total from the funding one, on
both surfaces.

Two things beyond the brief's letter, both in its spirit:

- The proposal also fires at **seed** time, not only on the currency round-trip, so a receipt the AI
  already detected as foreign-card opens with `Off account` filled rather than an empty required
  field the operator must poke.
- The transfer-line currency check is **unconditional** rather than cross-currency-only.
  `DockSplitService.resolveLine` refuses a wrong-currency transfer target on the single-currency
  path too, so scoping the check to cross-currency would have left that raw throw reachable.

`receipt_line.amount`'s stale V12 comment is corrected on `ReceiptLine`'s javadoc and in data-model
§13.2 (migrations are forward-only, so the applied file is untouched).
