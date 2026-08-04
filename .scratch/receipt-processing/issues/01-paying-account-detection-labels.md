# Paying-account detection almost never fires, and the editor silently defaults to Cash

Status: resolved
Category: bug
Severity: high
Area: Receipts — analyse seeding (§13.4 detection) × post-process editor (plan §9f/§9g)

Found while testing stage 9g. Two independent defects compound into the worst possible outcome:
the account is nearly always undetected, and the editor then quietly fills in the wrong one.

## 1. The detector recognises almost nothing

`PayingAccountDetector.detect` (accounts module) knows exactly two signals: the word `cash`/`bar`,
and a card's last four digits looked up against `account.card_last4`. The real parses in the DB
look like:

```
card
XXXX1234
card XXXX1234
```

`XXXX1234` and `card XXXX1234` resolve *if* `card_last4` is set — but a bare `card` carries no
digits, and cash was never once printed. In practice the paying account is almost always
undetected, even though nearly every receipt is paid with the same card.

There is no way to teach the system that "Girocard-EUR is the thing the AI calls `card`". A single
`card_last4` string is too narrow a vocabulary.

The prompt makes it worse: `ReceiptPromptBuilder` instructs the model to *normalise* the payment
line to `"card XXXX1234"` or `"Bar"`, which discards the printed text (`girocard`, `VISA`,
`kontaktlos`, `Mastercard`) that would otherwise be matchable.

## 2. Undetected silently becomes "Cash"

`receipt-process.html` renders the paying account as a `<select>` with **no empty option**:

```html
<select id="receipt-account" name="accountId" class="input">
  <option th:each="a : ${register.accounts()}" ... th:selected="${a.accountId() == editor.accountId()}"></option>
</select>
```

When `editor.accountId()` is null (nothing detected), no option carries `selected`, so the browser
pre-selects the **first** option — alphabetically first among the real accounts, i.e. `Cash`. On
submit that id is posted, and 9g's `ReceiptConfirmGate.checkAccount` sees a non-null, existing
account and lets it through. "Not detected" is indistinguishable from "the operator chose Cash",
so wrong-account transactions get booked and have to be found and fixed afterwards.

Owner decision (2026-08-04): **being forced into two clicks beats committing a wrong account.** No
guessing, no default-account fallback chain — undetected means empty, and Confirm blocks.

## Agreed design

**Rule 1 — cash.** If the signal names cash *and* the parse identified a currency, take the cash
account of that currency. If several accounts are marked as cash in one currency (shouldn't
happen), take the first alphabetically rather than refusing. No currency ⇒ no cash resolution.

**Rule 2 — labels.** `account.card_last4` becomes `account.detection_labels`: a comma-separated
list of substrings identifying the account in a payment line (`card, 1234, girocard`). Detection
splits on comma, trims, and tests each label as a **case-insensitive substring of the AI's
`account` string**. Blank labels are skipped (an empty string is a substring of everything).
**First match wins** — labels are deliberately *not* unique, since two cards can share their last
four digits by luck.

**Ordering** (this is what makes "first match wins" deterministic). Candidate accounts are ordered:

1. accounts whose currency matches the transaction currency first,
2. cash accounts last,
3. then by name, alphabetically.

Within one account, labels are tried in the order the operator typed them.

**Labels are tried before the cash keywords**, so an explicit label wins over the built-in
`cash`/`bar` vocabulary — necessary because `Barclaycard` and `Bargeldauszahlung` both contain
`bar`.

**Rule 3 — no match, no guess.** Leave the account empty; the editor shows an unselected
placeholder and 9g's existing hard block ("Pick the account the receipt was paid from before
confirming") forces the choice.

Same rules apply to the per-item `transfer` field (ATM/cashback lines, `ReceiptSeeder`) — it goes
through the same detector, so it inherits labels, cash-by-currency, and empty-on-no-match.

### Explicitly rejected during design

- **A separate `account_label` table**, uniqueness constraints on labels, longest-label-wins.
  Rejected: the comma-separated column is enough and labels legitimately collide.
- **A `default_account` per currency and a fallback chain** (label → currency default → base
  default → first alphabetically). Rejected as the same silent-wrong-account failure this issue is
  about, one step better disguised.
- **A partial unique index on the cash marker.** No new schema beyond the column rename; a
  duplicate cash marker is resolved by ordering, not refused.

## Agent Brief

**Category:** bug
**Summary:** Replace the single `card_last4` with a comma-separated `detection_labels` substring
vocabulary, make cash detection currency-aware, order candidates deterministically, and stop the
post-process editor from silently pre-selecting the first account when nothing was detected.

**Schema (V16 — a rename only):**
`alter table account rename column card_last4 to detection_labels;`
No new columns, no new indexes. `cash_account` stays exactly as it is. Existing values carry over
unchanged — a stored `1234` is already a valid single-label list.

**Key interfaces (durable names — locate them, don't trust paths):**

- `AccountDetection` — `cardLast4` → `detectionLabels`.
- `AccountService.updateDetection` — drop the `\d{4}` validation (labels are free text). Normalise
  on save: split on comma, strip each, drop blanks, rejoin in the order given; an all-blank list
  normalises to null. Preserve the operator's case (matching is case-insensitive) and their order
  (it is the tie-break).
- `AccountsController` + `account-edit.html` — the field becomes "Payment-line labels
  (comma-separated)"; remove `maxlength="4"`, `pattern="\d{4}"`, `inputmode="numeric"` and the
  `num` class, and give it a placeholder like `card, 1234, girocard`. The cash checkbox is
  unchanged. Explain in the field's help text that the labels are matched as substrings of the
  printed payment line, and that order matters.
- `AccountRepository` — replace `findByCardLast4` and `findCashAccount` with a single ordered
  candidate query over live real accounts (`asset`/`liability`, not `currency_leaf`, not
  `person_leaf`, `deleted_at is null` — the same set `RegisterService.OWN_ACCOUNT_TYPES` defines),
  ordered `(currency_code = :currency) desc, cash_account asc, name asc`. A null/unknown currency
  simply makes the first sort key inert. One query serves both the label scan and the cash lookup.
- `PayingAccountDetector.detect` — gains a currency argument: `detect(String signal, String
  currencyCode)`. Algorithm: blank signal ⇒ empty; else scan candidates in order, and within each
  its labels in order, returning the first case-insensitive substring hit; else, if the signal
  contains `cash`/`bar` and a currency was identified, return the first candidate that is marked
  cash *and* carries that currency; else empty. Upper-case/trim the parsed currency before use —
  it is raw model output, not a validated code.
- `ReceiptSeeder` — pass the parsed transaction currency into both `detect` calls (the header
  paying account and the per-item `transfer` target).
- `ReceiptPromptBuilder` — change the `account` field instruction from the normalising `"card
  XXXX1234" with the last 4 digits, or "Bar" for cash` to **the payment line as printed**, so the
  labels have the real text to match. Update the worked example consistently.
- `receipt-process.html` — add a first `<option value="">` placeholder (e.g. `— pick an account —`)
  that is `selected` when `editor.accountId()` is null. `ReceiptEditorForm.bind` already routes
  `accountId` through `ReceiptEditorText.parseId`, which maps blank to null, so an empty submission
  binds cleanly and hits the existing Confirm block. Verify Save (the lenient rung) still persists
  a draft with no account.

**Acceptance criteria:**

- [ ] `card`, `XXXX1234`, and `card XXXX1234` all resolve to `Girocard-EUR` when its labels are
      `card, 1234` — including `card` alone, which resolves today to nothing.
- [ ] Labels match case-insensitively as substrings; blank entries in the list match nothing.
- [ ] Two accounts sharing the label `1234` resolve deterministically by the documented ordering:
      transaction-currency match first, cash last, then name — and the ordering is covered by a
      test with a crafted tie.
- [ ] An explicit label beats the built-in cash keywords: a payment line reading `Barclaycard`
      resolves to the account labelled `barclaycard`, not to the cash account.
- [ ] A cash line on a CHF receipt resolves to the CHF cash account, not the EUR one; with two CHF
      cash accounts marked it takes the first alphabetically; with no identified currency it
      resolves to nothing.
- [ ] No match leaves the paying account empty; the editor shows the unselected placeholder and
      Confirm blocks with the existing message. Nothing pre-selects Cash.
- [ ] The per-item `transfer` field follows the same rules, including empty-on-no-match.
- [ ] V16 applies on a fresh container and an existing `card_last4` value survives as a label.
- [ ] `docs/data-model.md` §13.4 is rewritten to the label vocabulary and the ordering rule (it
      currently specifies `card_last4` and a single cash account); the changelog gets a
      scope-change entry, not a recap.
- [ ] `./gradlew check` green — detector rules in the unit tier (they are decision logic over a
      mocked repository), the ordered candidate query in `sqlLogicTest` (ordering *is* the logic),
      the account-edit round-trip in `integrationTest`.

**Out of scope:**

- Any default-account concept or fallback chain (rejected above).
- Sending labels to the model — they are matched locally and stay out of the prompt (ARCH-08); the
  prompt change is only about asking for the printed line verbatim.
- Cross-currency receipt commits — a label match on an account in another currency than the receipt
  is *correct* (you really did pay with that card) and 9g's currency block is the right outcome;
  that block stays backlogged (plan §14).
- The 9h batch work.

## Comments

Filed 2026-08-04 after owner testing of stage 9g. Design settled with the owner in the same
session; the rejected alternatives above were considered and turned down explicitly.

## Resolution

Implemented on branch `stage/9g` (same branch as 9g, at the owner's request). Every acceptance
criterion above is met and `./gradlew check` is green — **owner confirmation in the running app is
still outstanding**, in particular that the label matching fires on real receipts.

Two deviations from the brief, both deliberate:

- The candidate query filters `closed_at is null` and omits `not currency_leaf`. The `currency_leaf`
  marker only ever lands on category leaves, which the `type in ('asset','liability')` filter
  already excludes; `closed_at` is what actually makes the candidate set equal the register's
  pickable accounts, so detection cannot resolve to an account the screen won't offer.
- `DetectionLabels` was extracted after review: the split/strip/drop-blank rule was spelled once in
  `AccountService` (on write) and once in `PayingAccountDetector` (on read). One home, two callers.

The predicate deliberately mirrors `RegisterService`'s pickable set rather than sharing it — that
set lives in `ledger`, which depends on `accounts`, so reusing it would close a module cycle. If the
register's notion of a pickable account changes, this query has to change with it.

Not covered by a test, judged not worth one: that an existing `card_last4` value survives V16 as a
label (a column rename preserves data by construction).
