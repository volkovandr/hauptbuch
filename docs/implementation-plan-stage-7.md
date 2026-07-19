# Hauptbuch — Stage 7 Plan: Transaction Register & Entry Dock

**Status:** Draft v1.1
**Date:** 2026-07-11
**Owner:** volkovandr
**Parent:** `implementation-plan.md` (stage 7 — this doc is the detail the >30-line rule pushed out)
**Authoritative interaction design:** `ui-transaction-register.md`. This doc sequences the build; the
register doc owns every display and entry rule — section references below (§2.x/§3.x) point there.
Visual inspiration mock-ups: `docs/pic/register-*.png`.

> Stage 7 builds the two central surfaces — the newest-at-bottom register and the persistent
> entry/edit dock — as **five ordered sub-stages, each ending green and demoable** (plan §0). The
> split follows the stage-6 logic: read before write, simple before compound, and each
> schema-bearing piece (payee address, tags) lands with the sub-stage that consumes it.

**Shaping decisions (owner-confirmed, 2026-07-05):**
- **Tags are the last sub-stage (7e)** — register and dock work without them first; the tag schema
  (data-model §10) migrates only then.
- **Cross-currency entry follows edit/splits (7d)** — single-currency entry is usable day-to-day
  before the conversion mode lands.
- **7d re-scoped into 7d.0–7d.3 (2026-07-11):** `FX gain/loss` auto-booking is **retired** (the
  engine books no residual — data-model §6.3), and cross-currency entry now includes a
  **category-currency selector** (register §3.5) and **per-currency amount fields balanced in base**
  (register §3.8a). Transfers (`To→/From←` routing the counter-leg to a real account) land in 7d.3
  for single + split together. See §7d below.
- **Keyboard-first as each piece lands** — every picker/field ships with its key map from the
  start; the Q-UI-2 state machine is decided **piecewise, in the sub-stage that builds the piece**,
  never retrofitted. All key handling stays in the sanctioned `keyboard.js` leaf.
- **Filters yes, sorting deferred** — date-range / account / payee filters are in 7a; column
  re-sorting and the §2.7 balance-hide rule go to the backlog (plan §14) until missed.
- **Edit for cross-currency & transfer shapes carved into 7f, deferred past 7e (owner-confirmed,
  2026-07-14)** — entry (7d) creates cross-currency single-line, transfers, and split-with-transfer,
  but re-editing those shapes is refused for now; it lands after tags so the round-trip is built once
  against the final leg/tag shape. See §7f.
- **7c in two increments, split-edit bundled (owner-confirmed, 2026-07-08)** — edit/void shipped
  first (7c.1); the split panel (7c.2) delivers new-split entry *and* editing an existing split back
  into the panel together. The one open question — the sign of a mixed income+expense split line —
  was resolved 2026-07-09 (see 7c.2).

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

The list renders a real book (opening-balance transactions from stage 6 give live rows on day one);
no entry yet. Delivered: the SQL-resident register query (rows = postings to viewed accounts, §2.4;
Category cell summarising sibling legs with a `· +n` overflow hint; transfer legs as `⇄ Account`); the
per-account running balance by **rebinding the stage-3 TDD-ahead running-balance SQL to a real
`ledger` repository method** (closing that CLAUDE.md §6 marker); date/account/payee filters (§2.3,
account default = open non-system asset/liability accounts; fixed date-ascending — re-sorting deferred
to plan §14); newest-at-bottom rendering per §2.4–§2.10 (two-tone stored-hue zebra, German formatting,
muted `pending_review`). Minimal status-icon set only (Q-UI-4 stays open).

## 7b — Entry dock: new simple transactions ✅ **complete**

Keyboard-first entry of a single-category transaction through `recordTransaction`, the new row
appearing without a full reload. Delivered: the seeded `country` table + nullable
`payee.city`/`country` (§3.4); the dock (new mode, field order per §3.2); payee and category pickers
with inline create-new (`Name - City - Country` / `Parent - Child` parsing, reusing 6b's creation path
incl. implicit subdivision); the **lazy per-currency-leaf routing** of data-model §6.5 —
`resolveCurrencyLeaf` in `operations` (first CHF spend on `Food` promotes the leaf and lands
`Food-CHF`); sign-free amounts with the `+`/`−` override (§3.8, required — refunds are otherwise
unrepresentable); the single-ghost-category autofill (§3.9; Q-UI-3: plain mode, ties broken by most
recent use).

- **Q-UI-5 decided here:** commit re-renders the whole rows body for the active filter (the bounded
  re-fetch, §2.2) so a **backdated** entry re-threads every affected balance below it — correct by
  construction, the register being bounded to hundreds of rows. Chosen over targeted OOB slice-swaps;
  the scroll re-anchors via the `keyboard.js` leaf's `htmx:afterSwap` hook.
- **Browser smoke dropped** (plan §14): the entry→commit→correct-balance flow is covered at the
  controller/htmx acceptance level (MockMvc) in the integration tier.

## 7c — Edit mode, splits, void ✅ **complete**

The dock's second half — selecting, correcting, splitting, removing rows — in two increments:
**7c.1** edit/void, **7c.2** the split panel + posting notes.

**7c.1 — Edit + void.** Selecting a row loads it into the dock (edit mode, §3.1); Save re-threads in
place via `editTransaction`; changing Account or date recomputes both affected balance threads from
that date down (§3.3); a Void affordance soft-deletes via `voidTransaction`; the dock returns to new
mode after any commit. `DockEditService` (in `operations`) classifies a live transaction into the
dock's simple shape (one funding leg + one category leg, single currency) and refuses anything else
(transfer, opening balance, cross-currency, and — until 7c.2 — a split) with a clear message.

**7c.2 — Split panel + posting notes.** The split panel replaces the dock, seeded with one line at
the full amount; **Add/Remove line** full-re-render server-side with a new line defaulting to the
current remaining ("the rest", §3.10). Per-line category create-new works from every picker (the
browser posts ids back; `operations` never resolves categories itself — the `operations → categories`
cycle). Posting-level notes persist at both levels (§3.7). A live `remaining 0,00 ✓` readout and the
Save-button relabel live in the `keyboard.js` leaf (§1.6 — no new script, a display convenience only);
the **server** re-derives `remaining` and the funding side authoritatively at Save.

**Mixed-type split sign — owner-decided 2026-07-09 (the load-bearing rule; not in the register doc).**
One receipt = one transaction, even when it mixes income and expense — not two transactions. Each
line's **signed contribution** is `+amount` for an income category, `−amount` for an expense one (a
negative typed amount — a storno — flows through). The **funding leg = Σ(contributions)**: magnitude
`|Σ|`, side from `sign(Σ)`, with the owner convention **`Σ = 0` books on the debit side** (a net-zero
receipt — return five bottles, take one Cola, pay nothing — is legal and recordable). Each **category
leg = −contribution**, so everything sums to zero **by construction** for any mix. `remaining =
pre-entered total − |Σ|`; when `remaining ≠ 0` the Save button reads **"Save and update amount"** and
the server sets the funding total to `|Σ|` (no modal — the relabel *is* the confirmation). Because the
funding side is now *derived*, the panel shows a live direction cue — **"You'll pay / receive €X,XX"**
(neutral at `Σ = 0`) — so a split that unexpectedly nets to an inflow is visible before Save. Editing
an existing split re-opens it in the panel with each typed amount reconstructed as the sign-free
magnitude; non-simple shapes (transfer/opening/cross-currency) stay refused.

- **Out of scope here:** per-line tags → 7e; split beneficiary (`→ Person`) → stage 8.

## 7d — Cross-currency entry & transfers

**Re-scoped 2026-07-11** (data-model §6.3/§6.5, register §3.5/§3.8a). The FX-gain/loss automation is
being **removed**, and adding the category-currency selector grew 7d well past a single sub-stage. It
is now **four ordered packages**, each ending green and demoable. 7d.0 is engine work; 7d.1–7d.3 are
entry UX over the (post-7d.0) engine. Packages stay **separate, not merged**: 7d.1 builds and
de-risks the whole multi-amount machinery, which 7d.2 reuses at the header while adding
subdivision-in-base across lines — a distinct correctness surface deserving its own tests (plan §0).

### 7d.0 — Retire `FX gain/loss` auto-booking (engine) ✅ **complete**

**Goal:** the engine stops inventing a residual leg; a cross-currency transaction must balance in
base **from its entered legs**, and an unbalanced one is **refused** (data-model §6.3).

- **`LedgerService`:** remove the residual-booking path (the `FX_GAIN_LOSS_PARENT` lookup +
  `findLeafUnderParentNamed` insert). Cross-currency validation becomes: every leg has a non-null
  `base_amount` and `Σ base_amount = 0`; otherwise **reject** with the base gap in the message. No
  new leg is ever inserted by the engine.
- **`FX gain/loss` is un-seeded** — since no code path resolves it by name any more, it is dropped
  from the V2 seed and from `createCurrency`'s per-currency provisioning (6d). It becomes a **plain
  category** the user creates on demand and posts to like any other, arriving lazily on first use
  exactly as every category leaf does (data-model §6.3/§6.5). `Opening Balances` stays seeded — the
  engine still resolves *it* by name (opening-balance recording), which is the line: seed only the
  system leaves code looks up.
- **Tests:** `recordsParBalancedCrossCurrencyTransferWithNoFxResidual` stays green;
  `booksResidualOfNonParConversionToBaseFxLeaf` → becomes
  `rejectsCrossCurrencyWhenBaseAmountsDoNotSumToZero`; the currency-provisioning and
  schema-migration tests that asserted an FX gain/loss leaf are dropped. The `InvariantSqlLogicTest`
  base-sum cases already assert `Σ base ≠ 0` is a violation — keep.

**Done when:** a non-par conversion with unbalanced base is rejected (not silently patched); a
par/base-balanced conversion still records; `FX gain/loss` is no longer seeded or auto-provisioned
(a user category created on demand); `check` green.

### 7d.1 — Category-currency selector + cross-currency single-line entry *(was «a»)* ✅ **complete**

**Goal:** a plain income/expense can be entered in a currency other than the paying account's,
producing a correct cross-currency transaction — the whole multi-amount machinery, end to end.

- **Currency selector beside the category** (register §3.5): **defaults to the paying account's
  currency** (single-currency path, untouched in the ≥95% case); overriding it routes to that
  currency's leaf (`Food-CHF`) and **declares the transaction cross-currency**.
- **Progressive amount fields** (register §3.8a): the Amount field splits into **one per distinct
  currency** — 1 field single-currency; 2 when base is one side; 3 when **neither** side is base
  (base amount **pre-filled from `rate_as_of`**, confirmable, frozen on both legs so `Σ base = 0`).
- **Implied cross-rate** may be shown read-only; **never written back** to `exchange_rate` (§6.4).
- **No FX field**: an over-determined entry that can't balance is refused with the base gap shown
  (7d.0), prompting a manual `FX gain/loss` line.
- **Register display:** each leg already renders in its own currency thread (7a §2.9) — assert.
- **TDD:** leg-building + base-freeze + the reject-on-gap in the unit tier; the
  select-currency→reveal-fields→save flow (incl. the neither-is-base 3-field case) in MockMvc
  acceptance.

**Done when:** an EUR-card purchase of a CHF-priced item, and a CHF→USD purchase (neither base),
both enter from the dock, book balanced with frozen `base_amount`, and render in their native
threads; `check` green.

### 7d.2 — Cross-currency in splits *(was «b»)* ✅ **complete**

**Redesigned 2026-07-13 (owner-decided).** A split is one receipt → one merchant → one billing
currency → one rate, so it spans **at most two currencies** (funding + spending), fixed **once at the
header**, never per line. This retires the earlier per-line-currency design (per-line selector +
per-line base amount), which modelled a case that can't occur for a single receipt and produced an
incoherent readout (ambiguous total currency, mixed-currency `remaining`, dead base readout).

**Goal:** subdivide an already-cross-currency single-line entry (7d.1) across categories, with the
currencies and rate set once at the header and each line adding one spending-currency amount.

- **Currencies at the header, reusing 7d.1 wholesale:** the funding account + the one §3.5 spending
  selector fix both currencies and the shared rate; the header shows 7d.1's 1/2/3 total amount fields
  unchanged. The split panel keeps **no** per-line currency machinery.
- **One amount per line:** each line takes a single amount in the **spending** currency (the receipt
  figure); its **account-currency and base equivalents are derived** from the shared rate and shown
  **read-only per line** (register §3.8a/§3.10).
- **Balance in base, display in all:** only **base** sums to zero in the ledger; the panel shows a
  live `remaining` in **every currency in play** (all reach zero together). "The rest" defaulting
  extends to base, so the last line absorbs the rounding residual and `Σ base_amount = 0` holds
  exactly.
- **TDD:** the per-line derived-share allocation + the cross-line base sum-to-zero incl. the rounding
  residual in the unit tier; open→add lines→save (three-currency case) in MockMvc acceptance.

**Done when:** a split funded from a **non-base** account into a **non-base** spending currency (three
currencies) subdivides across ≥2 lines, shows `remaining` in all three converging to zero together,
commits with `Σ base_amount = 0` and frozen `base_amount`, and re-opens for edit; `check` green.

### 7d.3 — Transfers, single + split *(was «c»)*

**Progress (2026-07-14):** **single-line** transfers are done and owner-confirmed — same- and
cross-currency, `To →`/`From ←` routing the counter-leg to a real account, the counterpart-currency
reveal, and the register Category cell showing the other account with a `→`/`←` direction arrow
(this replaced the old `⇄` and fixed the empty-cell bug for a transfer between two viewed accounts).
**Split transfers are now implemented (ready to confirm):** a split line may be a `To →`/`From ←`
transfer to a real own account (signed by direction, not a category type), same- and cross-currency,
resolved through the shared `/categories/resolve`; the split-line sign math was extracted into
`SplitLineAmounts`. A transfer leg must be denominated in the split's spending currency — a third
currency would break the header's single shared rate (register §3.8a) and is refused with a clear
message. A split that *contains* a transfer leg is not yet re-editable in the panel (two-plus
own-account legs fall outside the panel's classify shape); it falls back to the "can't edit"
message. Re-editing that shape — together with cross-currency single-line and single-line
transfers, the other shapes the dock still refuses — is deferred to **7f** (below).

**Goal:** selecting **`To → <account>`** / **`From ← <account>`** in the Category field routes the
counter-leg to a **real account** instead of a category — making transfers enterable at last. Enabled
for **single-line and split in one package** (the routing is the same act in both).

- **Same-currency transfer** is trivial: the counter-leg is just an account, currency fixed by it
  (no selector — register §3.5), sign from the `⇄`/direction counterpart (register §3.8 table).
- **Cross-currency transfer** reuses the 7d.1/7d.2 multi-amount machinery unchanged (both legs are
  accounts; both currencies fixed; base balanced from entered legs).
- **Register display:** a transfer is already **two rows, one per leg** (7a §2.2/§2.6) — assert.
- **TDD:** counter-leg-to-account routing + direction in the unit tier; the `To→/From←` pick→save
  (same- and cross-currency, single and split) in MockMvc acceptance.

**Done when:** a same-currency and a cross-currency transfer both enter from the dock (single and
split), book balanced, and render as two native-thread rows; `check` green.

## 7e — Tags ✅ **complete**

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

## 7f — Edit for cross-currency & transfer transactions ✅ **complete**

**Goal:** extend edit mode to the transaction shapes the dock/panel still refuse to re-open, so
every transaction the entry surface can *create* is also *editable*. Deferred deliberately until
after tags (7e): the edit round-trip is then built once, against the final leg/tag shape, rather
than reworked when 7e changes what a posting carries.

Not yet round-trippable — each falls back to a "cannot be edited yet" message today:

- **Cross-currency single-line** — `DockEditService` refuses any leg carrying a frozen
  `base_amount` (register §3.8a); the shape must re-open preserving that frozen base.
- **Transfer, single-line** — two own-account legs fall outside the dock's one-funding-one-category
  shape (`To →`/`From ←` routing, §7d.3).
- **Split containing a transfer leg** — two-plus own-account legs fall outside the split panel's
  `classifySplit` shape.

Already editable, out of scope here: simple single-currency single-category (7c), and same- and
cross-currency **category-only** splits (7d.2).

- **TDD:** classification/reconstruction of each shape in the unit tier (the sign-free amount and
  frozen-base round-trip, mirroring `DockEditService`/`DockSplitService.load`); pick→edit→re-save
  in MockMvc acceptance.

**Done when:** each shape above re-opens into the dock/panel prefilled, re-saves via
`editTransaction` reproducing balanced legs (frozen `base_amount` preserved where cross-currency),
renders correctly in the register, and `check` green.

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
