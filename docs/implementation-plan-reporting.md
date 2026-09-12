# Hauptbuch — Reporting sub-plan (slices a–f)

**Status:** Draft v0.1
**Date:** 2026-09-12
**Owner:** volkovandr
**Companion to:** `reporting.md` (authoritative for every design decision),
`implementation-plan.md` (§3, the Reporting bullet), `docs/adr/0001-generic-report-engine.md`

> The **sequenced** build of the report engine, following the stage-7/9/import sub-plan pattern:
> this file is **temporary**. On completion it is deleted and a short summary is folded into §3's
> Reporting bullet in `implementation-plan.md` — the stage description is the record of what
> shipped, not a changelog of how it got there (CLAUDE.md §8a).
>
> Nothing here re-specifies design. Every "what" lives in `reporting.md`; this is order, slice
> boundaries, and the test tier each piece lands in.

---

## Why this order

Slice order is driven by **what the owner is missing right now**: "how much money I have, and how
that amount changes over time." So the main-page net-worth chart lands as early as its dependencies
allow — which turns out to be third, because Layouts need only **Presets**, and Presets are
code-defined (`reporting.md` §2), so saving Reports can come later without blocking it.

The alternative (strict dependency order, saving before charts) delays the main page by two slices
to buy nothing: the matrix is already useful at slice **a** even fully collapsed, since top-level
categories × month is the reading you start from anyway.

---

## a — The engine and the table renderer

The spec record, the grid, and the SQL. **No charts, no saving, no expansion** — a Report's spec
lives in the query string and the page renders a table.

- The `ReportSpec` / `ReportGrid` records and the `spec in → grid out` service boundary
  (`reporting.md` §14), in the `analytics` module.
- **Turnover** and **closing balance**, each in base and account currency, plus `count`
  (§5.1–§5.5); **legs** (§5.3); the two valuation rules kept apart.
- **One dimension per axis**, Date at month granularity, no nesting yet; Category / Account / Tag /
  Payee / Person / Currency / Account type (§4).
- **Scope** and the two filter kinds (§6), the operator set (§6.3), the four scope defaults with the
  header line (§6.4).
- **Legality**: `—` for the three meaningless aggregates, blank vs `0,00`, row suppression, row and
  column total toggles (§7).
- The **start/end anchor grammar** with the named shortcuts (§8.1) and partial-bucket labelling
  (§8.2).
- Presets: **category × month matrix** and **balance sheet** (§16).

**Tests.** `sqlLogicTest` first and carrying the weight (CLAUDE.md §6): every measure × currency
variant, the leg selections, cross-currency valuation under both rules, the `—` cases, the anchor
grammar's resolution, partial buckets, and the scope defaults. Unit tier stays thin — spec
validation and the legality rules that are decidable without the DB. Integration tier: the
controller renders the table, the header scope line, and the shortcut buttons.

**Done when** the matrix and balance-sheet Presets render correctly against real data, every
illegal aggregate prints `—`, and `./gradlew check` is green.

---

## b — Charts

The four renderers, server-rendered SVG, no new dependency (`reporting.md` §10).

- Line (with FR-ANA-09's **trend line**), bar, pie; **small multiples** for a row dimension on a
  chart; the pie's negative-measure refusal (§7.4).
- The **chart/table swap** inside one frame.
- Presets: **net worth over time** and **this month vs last** (§16).

**Tests.** Integration tier: each renderer emits well-formed SVG for a crafted grid, the swap
returns the table fragment, the pie refuses a negative measure with the message on the Report.
Geometry maths (scales, tick selection, the trend line's fit) is unit-testable and belongs there.

**Done when** all four Presets render, the swap works, and `./gradlew check` is green.

---

## c — Layouts and the main page

- Layout configuration (rows × columns → Frames, a dropdown per Frame, **Save layout**), no cap, no
  drag (`reporting.md` §11).
- The reporting page: **New report**, the Report list (empty until **d**), the Layout.
- The **main page as a 1×1 Layout**, defaulting to the net worth over time Preset, above the
  existing Balances panel.

**Tests.** Integration tier: the Layout persists and re-renders; the main page renders its Frame;
a Frame whose Report was deleted degrades to an empty Frame rather than a 500.

**Done when** the owner's main page shows net worth over time and the reporting page shows a
configured Layout. **This is the slice that closes the gap that prompted the feature.**

---

## d — Saving, listing and the URL

- The `report` table (`jsonb` spec + promoted name/renderer/Layout columns) and its migration.
- Save from an ad-hoc query-string Report; rename, duplicate, delete; `/reports/{id}`.
- Presets become copyable into owned Reports; `/reports/preset/{slug}` stays code-defined and
  non-deletable.

**Tests.** Integration tier: round-trip every repository method (CLAUDE.md §6 — these are plain
inserts/selects, so the integration tier, not `sqlLogicTest`); spec round-trips through `jsonb`
unchanged; a Preset cannot be deleted.

**Done when** a Report survives a restart and its link works from the reporting page.

---

## e — Expansion and nesting

- Expandable row hierarchies via htmx fragment swaps, with **remembered state** against the saved
  Report (§9.1).
- The **`auto` / collapsed / expanded** initial state and its one-node rule (§9.2).
- Parent rows as **subtotal** or **group header only**.
- The **`(unspecified)`** row for tags (§9.3).
- **Two dimensions per axis** (nesting), and the Date **ladder** with its two configurations (§8.2).

**Tests.** `sqlLogicTest` for the nested-grouping and subtree-rollup queries, including the tag
case where a parent row is *not* the sum of its children and the unspecified row carries the
difference. Integration tier: the expand fragment, the remembered state, `auto`'s one-node rule.

**Done when** `Tag is one of {Trips}` with `rows = [Tag, Category]` renders the owner's worked
example from `reporting.md` §9.2.

---

## f — Drill-down and CSV

- Cell → the reporting-owned transaction list, reusing the register's row fragment
  (`reporting.md` §12).
- The **register handoff** and its leg-selection rule, including the transfer and no-real-leg cases.
- **CSV export** of the raw grid (§13).

**Tests.** Integration tier: a cell's posting set matches the list it opens (the assertion that
makes the drill-down trustworthy); the handoff pre-selects the right account for a simple
transaction, a transfer, and a category-to-category correction; the CSV's shape.

**Done when** any cell opens its transactions and every Report exports.

---

## Cross-cutting, not a slice

- **The filter component** (`filter-groups.js`, one of the three sanctioned JS leaves) is
  generalised over the three hierarchies in slice **a** and gains acceptance coverage on **both**
  screens — it is explicitly not a fourth leaf (CLAUDE.md §1.6, `reporting.md` §14).
- **No materialization, no pre-emptive index** (`reporting.md` §15). If a slice measures slow on the
  Pi, that is a decision with a number attached, not a hedge added here.
- **Nothing is exposed over MCP**; the `spec in → grid out` boundary is shaped so the eventual tool
  is a wrapper (FR-MCP stays in §3).

---

## Changelog

- **v0.1 (2026-09-12):** Initial slicing from the grilling pass that produced `reporting.md` v0.1.
