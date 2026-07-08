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
- **7c in two increments, split-edit bundled (owner-confirmed, 2026-07-08)** — edit/void shipped
  first (7c.1 ✅); the split panel (7c.2) delivers new-split entry *and* editing an existing split
  back into the panel together. **Blocked on one open decision** — the sign of a mixed
  income+expense split line (see 7c.2).

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

## 7a — Register, read-only ✅ **complete**

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

## 7b — Entry dock: new simple transactions ✅ **complete**

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
- **Commit & repaint:** **Q-UI-5 decided here** — the commit re-renders the whole rows body for the
  active filter (the **bounded re-fetch**, §2.2's acceptable fallback), so a **backdated** entry
  re-threads every affected balance below it, not just the newest row. Chosen over `beforeend` +
  targeted OOB slice-swaps because the re-fetch is always correct by construction and the register is
  bounded to hundreds of rows; the newest-at-bottom scroll re-anchors via the `keyboard.js` leaf's
  `htmx:afterSwap` hook.
- **Browser smoke: dropped** (owner decision — see plan §14). The entry→commit→row-with-correct-
  balance flow is covered end-to-end at the controller/htmx acceptance level (MockMvc) in the
  integration tier instead.
- **TDD:** mode/tie-break query and payee-match query in `sqlLogicTest`; create-new parsing and
  sign resolution in the unit tier; dock rendering + swap behaviour + the commit/backdated repaint in
  integration (MockMvc).

**Done when:** a transaction is enterable end-to-end through the dock; payee and category create-new
work inline; a backdated insert repaints every affected balance below it; all three suites green.

## 7c — Edit mode, splits, void

**Goal:** the dock's second half — selecting, correcting, splitting, and removing rows. Two
increments: **7c.1** edit/void (done), **7c.2** the split panel + posting notes (planned below).

### 7c.1 — Edit mode + void ✅ **complete**

Selecting a row loads it into the dock (edit mode, §3.1); Save re-threads it in place via
`editTransaction`; changing the Account or date recomputes both affected balance threads from that
date down (§3.3, the backdated-insert slice); a Void affordance soft-deletes via `voidTransaction`
and the slice repaints; the dock returns to new mode after any commit. `DockEditService` (in
`operations`) classifies a live transaction's legs into the dock's simple shape — one own-account
funding leg + one income/expense category leg, single currency — reconstructs the sign-free amount
text, and resolves the per-currency leaf back to its semantic parent; anything else (transfer,
opening balance, cross-currency, and — until 7c.2 — a split) is refused with a clear message.
`DockEntry` gained a nullable `transactionId` (null = record, non-null = edit).

### 7c.2 — Split panel + posting notes (planned)

**⚠ Blocked on one decision before coding — the mixed-type split sign.** 7c.2 as designed below
treats a split as a decomposition of **one** funding flow: the funding total is fixed and every line
is a bare **magnitude** sharing the funding leg's direction, so the lines sum to the total and it
balances by construction (`remaining 0,00 ✓`). That assumes a single direction per split. A real
receipt can mix directions — e.g. Food items (expense) **and** a bottle-deposit return (income),
where the deposit should net **negative** against the spend. Which line goes negative, and how "the
rest"/remaining behave across two directions, is unresolved (why the *deposit* and not the *food*? —
no good answer yet). **Decide this first**; it changes the line/leg model below. Options to weigh:
keep splits single-direction and enter a mixed receipt as two transactions; or let each line's sign
follow its own category type and redefine "remaining" as the signed funding residual.

Assuming the single-direction model (revisit per the block above):

- **Open (§3.9→§3.10):** a `Split` affordance (button + `S` in `keyboard.js`) hx-posts the committed
  single dock line to `POST /register/split`, which returns the **split panel** fragment (replacing
  the dock) seeded with one line at the full amount and the committed category. The funding
  direction (outflow/inflow), decided at open from that category's type and any explicit `+`/`−`,
  rides along as a hidden field; all lines inherit it.
- **Per-line fields (§3.10, §3.7):** each line carries category + amount + note. The panel form is
  the single source of truth — `lineCategoryText[] / lineCategoryId[] / lineAmount[] / lineNote[]`,
  index-aligned — all submitted and re-emitted, so a re-render preserves resolved ids.
- **Per-line category create-new (owner-confirmed):** create-new must work from **every** category
  picker, split lines included. Reuse the browser bridge — `POST /categories/resolve` — but
  **parameterise its output field name** (`fieldName`, default `categoryId`) so each line resolves
  into its own list-bound hidden `lineCategoryId`. `operations` still never resolves categories
  itself (the `operations → categories` cycle); it only echoes the ids the browser posts back.
- **"The rest" defaulting (§3.10):** **Add line** / **Remove line** full-re-render the panel
  server-side; a new line's amount defaults to the current remaining (total − allocated).
- **Live `remaining` readout + Save-button label — in the `keyboard.js` leaf (owner-confirmed
  2026-07-08):** rather than an htmx round-trip per keystroke, a small client-side handler in
  `keyboard.js` (the sanctioned JS home, §1.6 — no new script, nothing threaded through templates)
  reacts to line-amount input: the panel exposes `data-split-*` hooks, keyboard.js sums the lines,
  writes the `remaining 0,00 ✓` readout, and relabels the Save button (below). This is a **display
  convenience only** — the server re-derives `remaining` authoritatively at Save (§1.7); there is no
  `/register/split/recalc` endpoint.
- **Balancing on Save — Save-button relabel (owner-confirmed 2026-07-08, replaces the earlier
  prompt idea):** when `remaining = 0` the button reads **Save** and commits as entered. When
  `remaining ≠ 0` it reads **Save and update amount** (relabelled live by the keyboard.js handler
  above) — clicking it (or pressing Enter) commits, and the **server** adjusts the funding total to
  the sum of the lines. No modal/prompt; the relabelled button is the visible, single-keystroke
  confirmation.
- **Commit:** `DockSplitService` (in `operations`) builds the funding leg + one leg per line
  (`PostingDraft.of(id, amount, note)` carries the posting note — added in 7c.1) and records/edits
  through the engine. The register Category cell already summarises the top one-to-three legs (7a
  renderer) — assert, don't rebuild.
- **Editing an existing split (owner-confirmed: land with new-split):** `GET /register/edit/{id}`
  loads a transaction with one funding leg + ≥2 same-direction category legs (single currency) back
  into the split panel, pre-filled; non-simple shapes stay refused (transfer/opening/cross-currency).
- **Out of scope here:** per-line tags → 7e; split beneficiary (`→ Person`) → stage 8.
- **TDD:** leg-building + remaining/adjust-total and the direction/"the rest" defaulting in the unit
  tier; the open→add→resolve→save flow, the *Save and update amount* path, and split edit-load in
  MockMvc acceptance (browser smoke stays dropped, plan §14). The keyboard.js live-remaining/relabel
  handler stays in the leaf (not unit-tested); the server's authoritative balancing is what the unit
  + MockMvc tiers assert.

**Done when:** a split is enterable from the dock, balances by construction with a live `remaining`
readout, and commits (adjusting the total when the button so indicates); an existing split re-opens
in the panel for edit; posting-level notes persist; the register cell shows the summarised
categories.

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
