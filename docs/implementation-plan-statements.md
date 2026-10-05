# Hauptbuch — Implementation Sub-plan: Bank Statement Reconciliation

**Status:** Draft v0.1
**Date:** 2026-10-04
**Owner:** volkovandr
**Design:** `docs/statements.md` (authoritative), `docs/adr/0003-postings-edited-in-place.md`,
`data-model.md` §15

> The build sequence for bank statement reconciliation, cut so that **one slice is one coding
> session** (the stage-7/9/import/reporting/recurring sub-plan pattern). Each slice ends with
> `./gradlew check` green and something to look at; none is marked ✅ without the owner's
> confirmation (CLAUDE.md §7.6). Deleted on completion, its summary folded into
> `implementation-plan.md` §3.
>
> Order rationale: the engine changes come first because they protect data the owner edits every
> day. CSV comes before PDF because it feeds the matcher real data with no AI cost or privacy step
> — bank A alone makes the matcher useful, and c–d is where the value is.

---

## 0 — Engine prerequisites

- **0a — Data gate (no code).** Show the owner the duplicate-leg query below again and **wait for
  their explicit confirmation** that it returns no `person_leaf = false` rows on **both** test and
  production. The owner fixes violations by hand in the register (production had two on
  2026-10-04). Nothing below starts before that confirmation.

  ```sql
  select p.transaction_id, t.date, a.account_id, a.name, a.type, a.person_leaf, count(*) as legs
  from posting p
  join transaction t on t.transaction_id = p.transaction_id
  join account a on a.account_id = p.account_id
  where t.deleted_at is null
    and a.type not in ('income', 'expense')
  group by p.transaction_id, t.date, a.account_id, a.name, a.type, a.person_leaf
  having count(*) > 1
  order by a.person_leaf, t.date;
  ```

- ✅ **0b — One leg per real own account.** `LedgerService` rejects a transaction with two postings to
  the same `asset`/`liability`/`equity` account that is not a person leaf, on record and edit, with
  a message naming the account. Unit tests; data-model §8 invariant 6 as a `sqlLogicTest`.
  *Done when:* the rule rejects, income/expense/person-leaf repeats still book.

- ✅ **0c — Postings edited in place.** `editTransaction` pairs legs by account; a paired leg is
  updated in place (`posting_id` kept) and keeps `reconciliation` when its amount is unchanged,
  drops to `unreconciled` when it changed; unpaired old legs deleted, new ones inserted; repeating
  accounts fall back to delete + insert. `ledger` gains the reconciliation-dropped interface,
  called with the downgraded posting ids and on void — no implementer yet, tested with a fake.
  Repository `updatePosting` / `deletePosting` round-trip in `integrationTest`.
  *Done when:* editing a payee or note of a Money-`R` transaction keeps its `R` (the live bug is
  fixed); ADR 0003's rules are covered by tests.

## a — Navigation

- ✅ **a — The `⋯` menu.** `.scratch/general-ux/issues/05-top-menu-overflow.md`.

## b — CSV in

- **b1 — Schema and CSV profiles.** The statement migration (data-model §15:
  `statement_profile`, `statement`, `statement_line`, `statement_match`); records and
  repositories; the profile screen (CSV dialect, sign mode, column map by header or index,
  window) with the **live preview** of the first rows; a Statements page skeleton linking to it;
  the Statements nav item. *Done when:* a profile for bank A can be created and previews its CSV
  correctly.
- **b2 — CSV upload.** Upload a file against a profile → a `statement` (file kept on the Pi) and its
  lines; rows the profile cannot read, or in a foreign currency, kept with a problem; the account
  proposed from the IBAN column via `detection_labels` and confirmed; period defaulted from the
  booking dates; the Statements list (account, period, profile, newest first, account filter).
  *Done when:* a real bank-A CSV lands as a statement with correct lines.
- **b3 — The statement page, before matching.** Header with editable period and balances; the line
  grid, editable; delete statement (no matches exist yet). *Done when:* a statement can be opened,
  corrected and deleted.

## c — Matching

- **c1 — The matcher.** The candidate queries for all four tiers (statements.md §4) — window, the
  account-leg sum, payee substring, exclusivity by closest date, overlaps, the reconciled-elsewhere
  exclusion for the wrong-account tier — as `sqlLogicTest`s with crafted cross-currency and boundary
  data. Line statuses and the extras table (unreconciled only, boundary label) rendered; counts in
  the header. *Done when:* opening a statement shows correct proposals for a crafted month.
- **c2 — Match actions.** Accept, Accept all exact, pick a same-account candidate, Unmatch (→
  `unreconciled`), the overlap decision (same movement / different transaction), delete statement
  with the keep-`reconciled`/reset question; matching confirms `pending_review`; `statements`
  implements the reconciliation-dropped interface. MockMvc acceptance. *Done when:* a CSV month can
  be reconciled to the extent the ledger already holds its transactions.

## d — The dock on the statement page

- **d1 — Create missing.** The dock embedded on the statement page; a missing line pre-fills date,
  account, amount, the longest payee substring, the payee's last category, and the bank-category
  label; Save books through `operations` and matches + reconciles in one database transaction.
  *Done when:* a missing line becomes a matched transaction without leaving the page.
- **d2 — The rest of the dock.** Amount differs (single-currency and cross-currency with base
  re-freeze and rate write-back), wrong-account candidates (account switch on Save), extras' Edit /
  Move / Void, the reconciled-leg notice in the register's dock. *Done when:* every status on the
  page has its action.

## e — PDF in

- **e1 — PDF text.** Upload a PDF against a PDF profile; PDFBox text extraction (a PDF without a text
  layer refused); account proposed from the unmasked text; pre-masking of own IBANs/account numbers
  and IBAN/BIC-shaped strings; the text editor; state `new`. *Done when:* a bank-B PDF shows its
  masked, editable text.
- **e2 — The AI call.** The statement parser (Anthropic SDK, synchronous), the built-in prompt + the
  `settings.statement_system_prompt` column and its editor + the profile's AI note; TOON decode into
  header and lines; `parse_raw`, token telemetry, frozen cost; `failed` with the error kept.
  *Done when:* a real bank-B and bank-C statement parse into correct lines and balances.
- **e3 — Fixing a bad parse.** Raw response editable with Re-seed (replaces lines and header, no API
  call), refused while any line is matched. *Done when:* a mis-dated line is fixable either way.

## f — Balance checks

- **f — Opening and closing.** Bank / ledger / explained / unexplained for both ends (statements.md
  §6.2); green per §6.5 shown on the page and in the list. *Done when:* a fully reconciled PDF month
  reads green, and a month with a boundary purchase explains its difference.
