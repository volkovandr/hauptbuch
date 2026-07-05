# Hauptbuch — Stage 7 Plan: Transaction Register & Entry Dock

**Status:** Draft v1.0
**Date:** 2026-07-05
**Owner:** volkovandr
**Parent:** `implementation-plan.md` (stage 7 — this doc is the detail the >30-line rule pushed out)
**Authoritative interaction design:** `ui-transaction-register.md` (v0.2). This doc sequences the
build; the register doc owns every display and entry rule — section references below (§2.x/§3.x)
point there. Visual inspiration mock-ups: `docs/pic/register-*.png`.

> Stage 7 builds the two central surfaces — the newest-at-bottom register and the persistent
> entry/edit dock — as **five ordered sub-stages, each ending green and demoable** (plan §0). The
> split follows the stage-6 logic: read before write, simple before compound, and each
> schema-bearing piece (payee address, tags) lands with the sub-stage that consumes it.

**Shaping decisions (owner-confirmed, 2026-07-05):**
- **Tags are the last sub-stage (7e)** — register and dock work without them first; the tag schema
  (data-model §10) migrates only then.
- **Cross-currency entry follows edit/splits (7d)** — single-currency entry is usable day-to-day
  before the conversion mode lands.
- **Keyboard-first as each piece lands** — every picker/field ships with its key map from the
  start; the Q-UI-2 state machine is decided **piecewise, in the sub-stage that builds the piece**,
  never retrofitted. All key handling stays in the sanctioned `keyboard.js` leaf.
- **Filters yes, sorting deferred** — date-range / account / payee filters are in 7a; column
  re-sorting and the §2.7 balance-hide rule go to the backlog (plan §14) until missed.

**Module-boundary note (decided up front — the 6d lesson, again).** `categories → ledger`,
`categories → operations`, and `operations → ledger` all already exist, so a dock controller in
`ledger` could call **neither** `categories` nor `operations` without closing a cycle. Placement:
- The **read-only register screen** (7a) needs only `ledger` + `accounts` → its controller lives in
  **`ledger`** (feature module owns its screen).
- The **dock's commit endpoint** (7b+) must orchestrate payee resolution, category-leaf resolution
  (which can trigger subdivision), and `recordTransaction` → it follows the 6d precedent: the
  htmx-driving controller lives in **`operations`**, alongside a new `resolveCurrencyLeaf`
  operation next to `subdivideAccount` (the lazy per-currency-leaf routing of data-model §6.5 *is*
  a structural domain operation). Final adjudication as always by `verify()`.

---

## 7a — Register, read-only

**Goal:** the list renders a real book correctly; no entry yet (opening-balance transactions from
stage 6 provide live rows on day one).

- **Register query** (SQL-resident): rows = postings to the viewed accounts (§2.4); per-row payee;
  Category cell summarising the sibling legs — biggest wins, `· +n` overflow hint; transfer legs as
  `⇄ Account`; per-account running balance by **rebinding the stage-3 TDD-ahead running-balance
  SQL to a real `ledger` repository method** (closing that marker per CLAUDE.md §6).
- **Filters (§2.3):** date range (default last 12 months), account multi-select (default: every
  open, non-system asset/liability account — "your own real accounts"; per-person debt accounts
  join the exclusion at stage 8), payee. Order is fixed date-ascending; re-sorting deferred.
- **Rendering:** newest-at-bottom with scroll-to-bottom on load (§2.1); fixed column order (§2.5);
  two-tone same-hue zebra from the stored account hue (§2.8); German formatting, base bare /
  non-base symbol-or-ISO (§2.9); green income, red negative balances; `pending_review` rows muted
  with `—` balance (§2.10 — nothing produces them yet; covered by fixture).
- **Status icons:** minimal set only — reconciliation glyph + pending clock; paperclip/recurring
  markers arrive with their features (Q-UI-4 stays open).
- **TDD:** `sqlLogicTest` for the register query with crafted scenarios (interleaved accounts,
  split summarisation, two-row transfer, filters, cross-currency rows, backdated ordering);
  integration test for the rendered page (hx attributes, zebra classes, formatting).

**Done when:** a fixture book renders exactly per §2.4–§2.10 defaults; filters work; every balance
thread is correct per account.

## 7b — Entry dock: new simple transactions

**Goal:** keyboard-first entry of a single-category transaction, committed through the stage-3
`recordTransaction`, with the new row appearing without a full reload.

- **Migration:** seeded `country` reference table (ISO 3166 names + common aliases incl. German —
  §3.4); `payee` gains nullable `city` / `country` columns.
- **Dock** (new mode), field order Date · Account · Payee · Amount · Category · Note · Add (§3.2;
  Tags slot in at 7e).
- **Payee picker (§3.4):** match on the normalised name+city+country concatenation; "Create new
  payee" always last; create-new parses `Name - City - Country` against the country list, opening
  the pre-filled mini-form.
- **Category picker (§3.5):** same pattern; create-new `Parent - Child` with inherited type,
  reusing the 6b creation path incl. implicit subdivision; **per-currency leaf resolved from the
  paying account at post time** — this sub-stage implements the lazy leaf routing of data-model
  §6.5 (`resolveCurrencyLeaf`, see the boundary note above): first CHF spend on `Food` promotes
  the leaf via subdivision and lands `Food-CHF`.
- **Sign-free amount** with the explicit leading `+`/`−` override (§3.8) — the override ships now,
  not later (refunds are unrepresentable without it).
- **Ghost autofill (§3.9):** most-common-category-per-payee as a single uncommitted suggestion.
  Q-UI-3 settled: plain statistical mode, ties broken by most recent use.
- **Commit & repaint:** newest row appends via `hx-swap="beforeend"` (§2.1); a **backdated** entry
  triggers the affected-slice refresh — **Q-UI-5 decided here**, lean OOB slice-swap with bounded
  re-fetch as the acceptable fallback (§2.2).
- **Playwright arrives:** test infrastructure + the first money-critical smoke (enter transaction →
  commit → row appears with the correct balance).
- **TDD:** mode/tie-break query and payee-match query in `sqlLogicTest`; create-new parsing and
  sign resolution in the unit tier; dock rendering + swap behaviour in integration.

**Done when:** a transaction is enterable end-to-end by keyboard alone; payee and category
create-new work inline; a backdated insert repaints every affected balance below it; the Playwright
smoke is green.

## 7c — Edit mode, splits, void

**Goal:** the dock's second half — selecting, correcting, splitting, and removing rows.

- **Edit mode (§3.1):** selecting a row loads it into the dock; save updates in place via
  `editTransaction`; dock returns to new mode after commit.
- **Re-threading (§3.3):** changing the Account (or the date) recomputes both affected accounts'
  balance threads from that date down — the same slice machinery as the backdated insert.
- **Void:** a delete affordance in edit mode calls `voidTransaction` (soft-delete); the row
  disappears and the slice repaints.
- **Split panel (§3.10):** `S` takes the committed single line into the inline-expand panel;
  "the rest" defaulting so the split balances by construction; `remaining 0,00 ✓` readout;
  per-line category + note (per-line tags at 7e, beneficiary at stage 8); the register cell shows
  the top one-to-three categories.
- **Notes (§3.7):** transaction level and split-line (posting) level.
- **Playwright:** a split-entry case joins the smoke.

**Done when:** rows are editable in place including account/date re-threading; splits balance by
construction and render summarised; void removes the row and repaints the thread.

## 7d — Cross-currency entry

**Goal:** the dock's conversion mode over the already-complete stage-3 engine — this sub-stage is
entry UX, not engine work.

- **Conversion entry:** a transfer between own accounts of different currencies takes **both native
  amounts** (the two real amounts stay the source of truth, frozen per data-model §6.3); the
  implied rate is shown, and `rate_as_of` proposes a starting value where a stored rate exists.
- The engine books frozen `base_amount`s and routes the non-par residual to the `FX gain/loss`
  leaf — verify end-to-end from the dock, including the neither-leg-is-base case.
- **Register display:** each leg is already its own row in its own currency thread (7a §2.9) —
  assert, don't rebuild.
- Optionally offer to store the conversion's implied rate as a `manual` `exchange_rate` row —
  decide at implementation.

**Done when:** a non-par EUR→CHF transfer entered in the dock books balanced with frozen base
amounts and the FX residual, and both legs render in their native threads.

## 7e — Tags

**Goal:** the tag dimension, end to end for entry (reporting rollups stay backlog).

- **Migration:** `tag` + `posting_tag` exactly per data-model §10.1; the tag entity is owned by
  **`categories`** (module map: shared taxonomy).
- **Chip field (§3.6):** keyboard-first JIRA-style chips — type, `↓` pick, commit, cursor stays;
  `:` hierarchy separator; backspace on empty removes the last chip; unknown text offers create.
  This is the most JS-heavy dock piece — it stays inside the `keyboard.js` leaf.
- **Semantics:** transaction-level tags expand to one `posting_tag` per leg (data-model §10.2);
  split inheritance per §3.6 (txn-level tags → all lines; otherwise each new line inherits the
  previous line's).
- **Register display:** tag chips render in the Category cell (per the mock-ups).
- **TDD:** repository round-trips in integration; expansion + inheritance rules in the unit tier;
  subtree/rollup queries are **not** built here (backlog, with tag reporting).

**Done when:** tags are enterable as chips, inherited into splits per the rules, persisted
per-posting, and visible in the register.

---

## Deferred out of stage 7

- **Column re-sorting + the balance-hide rule** (§2.7) → backlog (plan §14).
- **Everything person/debt:** arrow chips, the Account-vs-Category person rule, split beneficiary,
  Q-UI-1 → **stage 8**.
- **Status-icon sources:** receipt paperclip (stage 9+), recurring marker and `pending_review`
  producers (backlog).
- **Q-UI-6:** keep the leading minus on expense rows (the lean, now the default; revisit on use).

## Register-doc open questions — disposition here

| # | Disposition |
|---|-------------|
| Q-UI-1 | Carried to stage 8 (person-funded pure expense in the default register) |
| Q-UI-2 | Decided piecewise per sub-stage, keyboard-first from the start |
| Q-UI-3 | Settled at 7b: plain mode, ties broken by most recent use |
| Q-UI-4 | Minimal glyph set at 7a; finalisation stays open (cosmetic) |
| Q-UI-5 | Decided at 7b; lean OOB slice-swap, bounded re-fetch acceptable |
| Q-UI-6 | Keep the minus (default; revisit on use) |
