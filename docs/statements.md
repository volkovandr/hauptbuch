# Hauptbuch — Bank Statement Reconciliation

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.1
**Date:** 2026-10-04
**Owner:** volkovandr
**Companion to:** `requirements.md` (§5.8, FR-STMT-01–07; FR-IMP-05),
`data-model.md` (§3.6 the posting's `reconciliation`, §8 invariants, §15 the statement schema),
`ui-transaction-register.md` (§3, the dock this feature embeds),
`ui-receipt-processing.md` (the AI-parse conventions reused here),
`implementation-plan-statements.md` (the slices),
`docs/adr/0003-postings-edited-in-place.md`

> This document records the **design of bank statement reconciliation** — getting a bank's
> statement into the app, matching its lines against the ledger, and closing the gaps — together
> with the reasoning, in keeping with the house rule that the *why* must survive long after the
> *what* is code. Grilled with the owner 2026-10-03/04.
>
> Scope note: it also **replaces the planned generic CSV importer** (FR-IMP-05). Importing a bank
> CSV and reconciling it are the same act — a line the ledger lacks is created — so there is one
> CSV path, the statement profile (§3.1), not a second one built on the QIF staging apparatus.
>
> Terminology is pinned in `CONTEXT.md` (§Statements) and is not redefined here.

---

## 1. The shape, in one paragraph

The operator uploads one **statement** for one account — a CSV through a **statement profile**, or a
PDF whose extracted text they redact and send to the AI. Its **lines** are matched live against the
ledger's postings on that account. The **statement page** is a readout, recomputed on every view:
how many lines are matched and reconciled, which lines are **missing** from the ledger, which
postings are **extra**, and whether the opening and closing balances agree. The operator works it
one transaction at a time through the dock embedded on the page, every change committed to the
ledger at once, until the page is green. There is **no reconciliation operation** to start, commit
or roll back — this is how the owner reconciled in Microsoft Money, by hand, and the feature
removes the pain without changing the rhythm. Statements need not chain: any month, in any order.

**Why this matters.** After ~26,000 imported Money transactions and ~1,000 receipts, the ledger's
balances drift from reality — lost receipts, transactions booked to the wrong account, foreign
charges entered before the real rate was known. The statement is the only evidence of what actually
moved on the account. The primary use is the **monthly routine**; the multi-year backlog is the same
routine, repeated.

---

## 2. Four objects

- **Statement profile** — how to read one source: a CSV dialect and column map, or a PDF bank's AI
  note. Also carries the matching date window (§4.1). Managed from the Statements page.
- **Statement** — one uploaded file for one account: the file on the Pi (ARCH-07), the period, the
  optional opening and closing balances, and for a PDF the text sent and the AI's response.
  Persisted — it is the evidence you come back to.
- **Statement line** — one booking the bank reports. One shape for every source (§3.3).
- **Match** — the confirmed link between a statement line and a posting. The only result of
  reconciliation that is stored; everything else on the page is computed (§4.5).

---

## 3. Getting a statement in

### 3.1 CSV — a statement profile, no AI

A CSV profile holds the dialect — delimiter, quote character, encoding, header rows to skip, decimal
separator, date format — the **sign mode** (one signed amount column, or separate debit and credit
columns), and a **column map** from file column to statement-line field. A column is named by its
**header text**, falling back to a 1-based index for a file without a header row: header names
survive a bank adding or reordering columns, indices do not. The map is stored as typed columns on
the profile, not a JSON bag (the `settings` legibility stance).

The profile editor shows a **live preview** of the first rows parsed with the current settings, so a
wrong date format is seen before it is saved, not after 300 lines are misread.

A CSV carries no balances (the owner's bank A exports none), so a CSV statement is checked on
**turnover only** until the operator types balances in (§3.4). A row the profile cannot read, or whose
currency column differs from the account's currency (§3.3), is kept as a line with a **problem**
shown on it and is never matched.

Nothing about a CSV profile is bank-specific: any CSV — another app's export — can be read through
one, every line arriving as missing and created through the dock. That is FR-IMP-05.

### 3.2 PDF — local text, operator-redacted, then the AI

The owner's banks B and C issue PDFs only: a formal letter, then the transaction table, then
free-text notes. Only the table and the two balances matter. A trial parse of a real statement with
the hosted model worked well and cheaply; the concern is **privacy** — a statement is highly
sensitive. So **the PDF itself is never sent**:

1. **Extract the text locally** (PDFBox). Bank-generated statements carry an exact text layer. A PDF
   **without** one (a scan) is refused in v1 — there is no image path.
2. **Pre-mask** the text before showing it: the operator's own IBANs and account numbers (from the
   accounts' `detection_labels`, §3.5) and any other IBAN/BIC-shaped string. Counterparty *names*
   stay — matching needs them.
3. **The operator edits the text freely** — removing their name, address, anything else — in an
   editor on the processing screen. Pre-masking is a starting point, undoable by editing.
4. **Send exactly that text** with the parsing instructions: the operator-editable statement system
   prompt (`settings`, NULL = built-in default) plus the profile's AI note (e.g. "Beschreibung carries
   the original currency and rate"). **One synchronous call** — the work is interactive, so there is
   no batch mode.
5. **Store** the text as sent, the raw response, token telemetry and the frozen cost, and the seeded
   lines and header — the receipt conventions unchanged (TOON via jtoon, cost from the `settings`
   price rates at parse time, the same model setting).

The AI makes mistakes (one real statement mixed American and European dates). Both repair surfaces
receipts have exist here: the **raw response is editable**, and *Re-seed* decodes the edited text and
**replaces the lines and header** without another API call; the **lines are editable** individually
in a grid. Re-seed is **refused while any line of the statement is matched** — unmatch first, the
equivalent of receipts refusing a committed receipt.

ARCH-08 holds: the statement is the document being parsed, and nothing from the ledger is sent.

### 3.3 The statement line

Every parser — the CSV map and the AI alike — produces one shape: **booking date**, value date
(optional), **amount in the account's currency** (signed, `+` = money in), and, when the bank shows
a foreign charge, the **original amount, currency and rate** (bank C prints them inside the
description); plus counterparty, description, the **bank's own category** (raw text — German, never
a Hauptbuch category, shown only as a hint, §6.4), and the raw source text.

A line's amount is **always** in the account's currency. A foreign amount arrives only as the
line's original amount; a CSV row whose currency differs from the account's is a problem, never
silently converted.

### 3.4 The header: period and balances

A PDF yields its period and its opening and closing balances from the AI; a CSV's period defaults
to its first and last booking date and it has no balances. **All four are editable** — the AI misreads
them as easily as a line. Balances present ⇒ the statement is checked on **balance + turnover**
(§6.2); absent ⇒ on **turnover only**. Typing balances into a CSV statement switches it.

### 3.5 Which account

The upload **proposes** the account from the file: the IBAN column of a CSV, or an account number
found in the PDF's extracted text — before masking, locally. The match is against the account's
existing `detection_labels` field (the receipts' paying-account detection, data-model §13.4), which
now also holds IBANs and account numbers. The operator always confirms; a statement belongs to
exactly one account.

---

## 4. Matching

### 4.1 The date window

The ledger's date is the purchase date (the receipt's timestamp); the bank books days later. So
matching never compares dates exactly, and **never rewrites `transaction.date`** — the bank's date
lives on the statement line. A posting is in a line's window when its transaction date lies between
**10 days before and 3 days after the booking date** — asymmetric, because the ledger date is almost
always the earlier one. The window is a **per-profile** setting (a credit card may need a wider one).

### 4.2 The four tiers

Candidates for a line are live postings within the window. They are offered in tiers, **all of them
proposals the operator confirms**:

| Tier | Account | Amount | Payee | Meaning |
|------|---------|--------|-------|---------|
| **exact** | the statement's | equal | — | the normal case |
| **amount differs** | the statement's | different | similar | the rate wasn't known, a typo, a tip |
| **ambiguous** | the statement's | equal | — | more than one exact candidate (two €3,50 coffees); the operator picks |
| **wrong account** | another own real account | equal | similar | booked to the wrong account |

**Payee similar** means the transaction's payee name is a case-insensitive substring of the line's
counterparty or description text (`ShopAaa` in `SHOPAAA SAGT DANKE 4711`). Nothing is learned.

**"The amount" is the sum of that account's legs in the transaction** — one leg by construction
(data-model §8, invariant 6).

### 4.3 Who may be a candidate

- On the **statement's own account**: any live posting not already matched to another line of this
  statement — **including postings already `reconciled`**. Money's imported `R` marks are trusted
  exactly like marks this feature sets; such a posting matches normally and simply gains its match.
- On **another account** (the wrong-account tier): only an own real account (never a person leaf, a
  category or equity), and only postings **not `reconciled`** — a posting proven by that account's
  own statement is not in the wrong account.

### 4.4 Exclusivity and overlaps

- Within one statement a posting is proposed to **the lines of its best tier**. For the lower tiers
  (amount differs, wrong account) that is one line — the closest booking date wins. An **exact**
  posting is offered to **every** line it is exact for: the date is a weak signal, so the operator
  decides. Such a line is *competing* (it fits another line too): it has Accept, and **Accept all
  exact skips it**. Once one line takes the posting the others lose it and fall to their next
  candidate, or to missing.
- Matching is **1:1** — one line, one posting. A line that covers two ledger transactions, or two lines
  that cover one, is out of scope in v1.
- CSVs can be pulled for any range, so two statements of the same account may **overlap**. A posting
  may carry matches from **several** statements, never from two lines of the **same** statement. When a
  line's best candidate is already matched on another statement, the operator decides: *the same
  bank movement* (match it again — a no-op on its state) or *a different transaction that looks the
  same* (the line is missing and gets created).

### 4.5 Live, not stored

Only statements, lines and matches are stored. Proposals, tiers, missing lines, extras, percentages
and the balance checks are **recomputed on every view** — an edit made in the register is reflected
the next time the statement is opened, and reopening an old statement simply re-runs the matcher.
There is no snapshot to go stale and no stored "done" state.

---

## 5. What a match does

- **A confirmed match sets the posting to `reconciled`** — from `unreconciled` or `cleared` — and
  **confirms a `pending_review` transaction** (a recurring occurrence, a receipt placeholder). This
  flow **never sets `cleared`**; the state stays for manual use and for Money's imported `C` values.
- **A match exists only on a `reconciled` posting.** Whatever drops a posting out of `reconciled` from
  the ledger side drops its matches, on every statement, with it: an edit that changes the leg's
  amount or its **date**, moves it to another account (data-model §3.6, ADR 0003), or voiding the
  transaction. A date change drops every `reconciled` leg of the transaction.
- **Unmatch** (one line, a match made by mistake) removes **that statement's** match only. The
  posting goes back to `unreconciled` — even a Money-`R` posting; it will normally be matched to the
  right line at once — unless another statement still matches it, in which case it stays `reconciled`.
- **Editing a matched line's booking date or amount** (the line grid) unmatches it the same way when
  saved; the page says so beside Save. Text fields never affect a match.
- **Deleting a statement** removes its matches and **asks** whether its postings stay `reconciled` or
  go back to `unreconciled`. The rows are soft-deleted; the file stays on the Pi unless the operator
  chooses to remove it.
- A match is per **posting**, not per transaction: a Giro → Visa transfer's Giro leg is reconciled from
  the Giro statement and its Visa leg from the Visa statement, independently, possibly months apart.

---

## 6. The statement page

### 6.1 Layout

Top to bottom: a **header** (account, period, profile, the two balance checks, the counts — matched,
reconciled, missing, extra), the **lines table**, the **extras table**, and the **dock** embedded at the
bottom. The PDF text, the raw response and the line grid sit on a separate tab of the same page, as
on the receipt processing screen. Clicking any row loads the dock; nothing navigates away. A small
*open in register* link stays for the unusual.

### 6.2 The balance checks — opening and closing

The ledger's dates are purchase dates, so its balance on 31 May legitimately differs from the bank's
closing balance by every purchase made in May and booked in June. A plain comparison would almost
never agree. Each check therefore shows four figures: the **bank's** balance, the **ledger's** balance
for the account at that date, the **explained** part of the difference — postings matched to this
statement but dated outside its period, and boundary extras (§6.3) — and the **unexplained**
remainder. Only the unexplained remainder counts. A CSV statement without balances shows no checks.

### 6.3 Lines and extras

- **Lines table** — one row per line with its status: *matched* ✓, an *exact* proposal (Accept), a
  candidate list (pick one — the wrong-account tier included), or *missing* (Create). **Accept all
  exact** confirms every unambiguous exact proposal at once — not a competing one (§4.4).
- **Extras table** — postings on the account, dated in the period, **not matched** to this statement and
  **not `reconciled`** (and not already proposed to a line, which shows them), shown as register-style rows with Edit / Move to account / Void. A `reconciled`
  posting is never an extra: a transaction booked in May but valued by the bank in June is an extra on
  May's statement until June's is reconciled, and then disappears from May's. An extra dated in the
  window's last days of the period (or its first days, the mirror case) is labelled **probably on the
  next statement** — a **boundary extra** — and does not count against green. Nothing about it is
  stored; the label is computed.

### 6.4 The embedded dock — every save links

Saving the dock from a statement row **matches that leg to the line and sets it `reconciled` in the
same operation**; a save that fails validation matches nothing. What the dock is pre-filled with:

- **Missing line** — date = booking date, account = the statement's, amount = the line's (a foreign line
  pre-fills the cross-currency fields from its original amount), payee = the **longest** payee name that
  is a substring of the bank text (none ⇒ empty, with the bank text shown as a label), category = that
  payee's most recent category. The bank's own category is shown as a **label beside the category
  picker**, never in it. Missing lines are created **one at a time** — bulk pre-booking would skip the
  categorisation that is the point. A **transfer to an account in another currency** (the bank only
  knows its own side) asks for the **counterpart amount**, proposed from the rate feed (blank when no
  rate is on file) and a base amount when neither account is the base currency; Save is the
  confirmation, and a wrong figure is corrected when the other account's statement is matched.
- **Amount differs** — the transaction, with the statement's leg set to the bank amount. For a
  **cross-currency** transaction the foreign legs' `base_amount` is re-frozen to the bank's amount (and
  the foreign native amount pre-filled from the line's original amount when it differs); the existing
  rate write-back then records the real rate (data-model §3.7). For a single-currency transaction the
  counterpart is left to the operator — splits cannot be resolved automatically.
- **Wrong account** — the transaction with its leg moved to the statement's account (the existing
  re-threading).
- **Extras** — Edit, Move to account, or Void, through the same dock.

Editing a reconciled leg **from the register** shows a muted notice in the dock ("Giro leg reconciled —
statement 2026-05"), with no block; if the edit changes the amount, the leg drops to `unreconciled`
and its match goes (§5).

### 6.5 Green

A statement is green when **every line is matched**, there are **no extras** other than boundary extras,
and — when balances are present — **both unexplained remainders are 0**.

---

## 7. Engine changes (ADR 0003)

Two changes to the ledger engine, both prerequisites:

- **One leg per real own account per transaction.** A transaction may not carry two postings to the same
  `asset`/`liability`/`equity` account — person leaves excepted. Income and expense accounts and person
  leaves may repeat (two receipt lines `for Max` are two postings to Max-EUR). A violation is
  **rejected** in `LedgerService` validation, never merged (merging would lose per-line notes and tags).
  This makes "the leg on this account" well-defined, which the 1:1 match and the in-place edit both
  rely on. The owner's production book had two violating transactions; they are fixed by hand before
  the rule lands (plan slice 0a).
- **Postings are edited in place.** `editTransaction` no longer deletes and reinserts every posting. It
  pairs the new legs with the existing ones **by account**: a paired leg is updated in place (amount,
  base amount, note, tags) and keeps its `posting_id`; its `reconciliation` is kept when the amount is
  unchanged and dropped to `unreconciled` when it changed; an old leg with no partner is deleted
  (its match cascades); a new leg is inserted `unreconciled`. Legs on a repeating account (income,
  expense, person leaves) fall back to delete + insert — none of them is ever matched. A posting
  whose `reconciliation` drops, and every posting of a voided transaction, is reported through an
  interface `ledger` owns and `statements` implements, which removes their matches — the
  dependency inversion recurring already uses for person merges. This also fixes a live bug: every
  dock edit used to reset every leg to `unreconciled`, silently wiping Money's imported `R` marks.

---

## 8. Navigation

**Statements** joins the top-level menu. The menu is already nine items wide, so it is regrouped: the
top level keeps **Register, Receipts, Statements, Reports, Recurring, People**; a trailing **`⋯`** menu
holds **Accounts, Categories, Import, Settings** — a native `<details>` dropdown, CSS-only, **no new JS
leaf** (`.scratch/general-ux/issues/05-top-menu-overflow.md`).

The **Statements page** lists every statement — account, period, profile, live status summary — newest
first, filterable by account, and holds the upload. The **statement profiles** screen is reached from
it, not from the top menu.

---

## 9. Module placement and testing

- **`statements`** owns profiles, statements, lines, matches, the CSV reader, the PDF text extraction
  and the parser client. It depends on `ledger` (postings, `PayeeService`, `SettingsService`),
  `accounts` (detection, the post-to set) and `operations` (the dock's commit services). It implements
  `ledger`'s reconciliation-dropped interface. The statement-mode dock save is a `statements` service
  that calls the `operations` commit path and records the match in **one** database transaction — the
  receipt-confirm precedent; `operations` never depends on `statements`.
- **Tests:** the matcher's candidate queries — window, tiers, exclusivity, overlaps, the
  reconciled-elsewhere exclusion — are SQL-resident logic, **`sqlLogicTest`** with crafted
  cross-currency and boundary cases. The in-place edit and the uniqueness rule are unit-tested in
  `LedgerService` with the repository mocked, and its new repository methods round-trip in
  `integrationTest`. The statement page's flows (accept, unmatch, dock save links) are MockMvc
  acceptance in `integrationTest`. No new JS, so nothing for `browserTest`.

---

## 10. Out of scope (v1)

- Batch parsing (the Batches API) — the PDF flow is interactive.
- 1:n / n:1 matches.
- Scanned PDFs without a text layer.
- A stored "done" or pause state — green is computed.
- **Receipt duplicate detection / link-to-existing** at receipt confirm (Q-RX-2) — deferred until this
  matcher existed; it is now unblocked but stays a separate §3 follow-on with its own open question.
- Matching recurring occurrences against statement lines *ahead* of booking (FR-REC-03's deferral) —
  a booked pending occurrence is matched like any posting and confirmed by the match.

---

## 11. Open questions

| # | Question | Status |
|---|----------|--------|
| Q-ST-1 | Default date window (−10/+3 days) right for banks A–C? | Open — tune from use; it is per profile |

---

## Changelog

- **v0.1 (2026-10-04):** Initial design from the grilling session with the owner.
