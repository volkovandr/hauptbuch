# Hauptbuch — Reporting sub-plan (slices a–f)

**Status:** Draft v0.10
**Date:** 2026-09-23
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

## a — The engine and the table renderer ✅ complete (owner-confirmed 2026-09-13)

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

## b — Charts ✅ complete (owner-confirmed 2026-09-13)

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

## c — Layouts and the main page ✅ complete (owner-confirmed 2026-09-13)

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

## d — Saving, listing and the URL ✅ complete (owner-confirmed 2026-09-13)

- The `report` table (`jsonb` spec + promoted name/renderer/Layout columns) and its migration.
- Save from an ad-hoc query-string Report; rename, duplicate, delete; `/reports/{id}`.
- Presets become copyable into owned Reports; `/reports/preset/{slug}` stays code-defined and
  non-deletable.

**Tests.** Integration tier: round-trip every repository method (CLAUDE.md §6 — these are plain
inserts/selects, so the integration tier, not `sqlLogicTest`); spec round-trips through `jsonb`
unchanged; a Preset cannot be deleted.

**Done when** a Report survives a restart and its link works from the reporting page.

---

## d2 — The Layout, read-only ✅ complete (owner-confirmed 2026-09-14)

Reworks slice **c**'s screens to `reporting.md` §11; no engine change.

- **One Frame fragment**, replacing `main-frame.html` and the Frame markup inside
  `layout-config.html`: the Report's name as heading, the rendering, **Open report →** at the bottom
  right, a muted *No report* when unconfigured.
- **`/reports`**: the Layout read-only, then **My reports** and **Presets** lists, then **Edit
  layout**.
- **`/reports/layout`**: today's rows × columns and per-Frame dropdowns (each above the same Frame
  fragment), **Save layout** and **Cancel** returning to `/reports`.
- **Main page**: the Frame fragment in place of the captioned picker; the picker moves below the
  Balances panel; the Frame is hidden when no Report is chosen.

Until **d3**, Open report leads to the existing read-only Report page.

**Tests.** Integration tier: `/reports` renders Frames with their headings and no configuration
inputs; `/reports/layout` persists and Cancel does not; the main page renders the heading and the
bottom picker, and hides the Frame when unconfigured.

**Done when** the reporting page and the main page read as clean Frames headed by their Reports'
names, the Layout is edited on its own page, and `./gradlew check` is green.

---

## d3 — The Report page is the editor ✅ complete (owner-confirmed 2026-09-20)

Everything the engine supports **today** becomes editable on the Report's own page
(`reporting.md` §11a). Later slices add their own controls to it.

- **Spec ↔ query-string binding** — the full spec, both directions, and the page choosing between a
  URL draft and the saved spec; `hx-replace-url` on every re-render.
- **Actions**: Save / Save as new report / Discard changes / Delete, the Unsaved marker; a Preset
  offers Save as new report only; **New report** at `/reports/new`. The "Manage this report" panel,
  Duplicate and Preset "Copy to my reports" are removed.
- **The settings strip** (§11a.2): Rows & columns, Measures, Scope, Filters, Date range, Display, and
  the always-visible Renderer. The chart/table swap is removed.
- **Live vs Apply** (§11a.3); the unapplied-changes state; illegal combinations unenterable or
  messaged.
- **Filters** (§11a.5): fixed per-field sections, reading switches, Payee's operator; hierarchy
  pickers storing nodes — `filter-groups.js` generalised (node mode, no hard-coded field name) with
  acceptance coverage on the register **and** the Report page.
- **Date range** (§11a.6): shortcuts, Date/Relative endpoints, the resolved-date label swap.
- **Help markers** (§11a.7), including on `—` cells.
- **Engine**: `Scope` loses `accountSubtreeRoots`; `FilterField` loses `LIFECYCLE`; Account type
  filters are transaction-level only; `ReportSpec` rejects two filters on one field; the
  scope-misses-the-dimension message.

**Tests.** Unit tier: the spec ↔ query-string binding round-trips every spec shape (the one piece
whose bugs silently change a Report), and the new `ReportSpec`/`ReportFilter` rejections.
Integration tier: a draft URL renders its spec and the Unsaved marker, no parameters renders the
saved spec; Save / Save as new / Discard / Delete; a Preset has no Save or Delete; each settings
group renders its controls and an Apply group's control does not re-render on change; a ticked
hierarchy node round-trips as the node; `—` carries its help marker. The filter leaf's acceptance on
both screens.

**Done when** the owner can open the matrix Preset, change its scope, filters, measures, range and
renderer, save it as a Report of his own, and reopen it from a Frame — and `./gradlew check` is
green.

---

## e — Expansion and nesting ✅ complete (owner-confirmed 2026-09-23)

Adds its own controls to the d3 editor: the second dimension per axis, the initial expansion state,
parent rows as subtotal or header, and the Date ladder.

- Expandable row hierarchies via htmx fragment swaps, with **remembered state** against the saved
  Report (§9.1).
- The **`auto` / collapsed / expanded** initial state and its one-node rule (§9.2).
- Parent rows as **subtotal** or **group header only**.
- The **`(unspecified)`** row for tags (§9.3).
- **Two dimensions per axis** (nesting), and the Date **ladder** with its two configurations (§8.2).
  Date rows expand in place from month/week into days; the ladder's **year rung is deferred** to a
  later slice (not yet planned in detail).

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

- **v0.10 (2026-09-23):** Slice e marked complete (owner-confirmed). No scope change (routine);
  follow-ups found in testing are filed as `.scratch/reporting/issues/07`–`11` for triage.
- **v0.9 (2026-09-23):** Scope change in slice e: the Date ladder's **year rung is deferred** out of
  e (owner decision); Date rows expand month/week → day only. Also decided: Date's `auto` starts
  collapsed (`reporting.md` §9.2).
- **v0.8 (2026-09-20):** Slice d3 marked complete (owner-confirmed). No scope change (routine).
- **v0.7 (2026-09-14):** Slice d2 marked complete (owner-confirmed). No scope change (routine).
- **v0.6 (2026-09-13):** Scope change: **slices d2 and d3 inserted** before e. No slice had owned the
  UI for editing a Report (v0.5 dropped the spec builder from d without re-homing it), and the
  owner asked for the reporting and main pages to show Frames cleanly. Designed in `reporting.md`
  v0.2 (§11, §11a); e gains its controls in the d3 editor. Letters e and f are kept because code
  comments already cite them.
- **v0.5 (2026-09-13):** Slice d marked complete (owner-confirmed). Scope change: the ad-hoc
  query-string spec builder was not built — no dimension/filter/measure picker UI exists anywhere
  in the app, and the owner confirmed building the full spec↔query-string codec plus a generic
  render endpoint was out of scope for this slice; saving is "copy a Preset, then rename" only.
  Also closes a gap `V25`'s own migration comment had anticipated for this stage: a Frame (the main
  page's and the reporting page's Layout alike) can now reference a saved Report as well as a
  Preset.
- **v0.4 (2026-09-13):** Slice c marked complete (owner-confirmed), built as two reviewed work
  packages — the main page's 1×1 Layout, then the reporting page's configurable Layout. No scope
  change (routine).
- **v0.3 (2026-09-13):** Slice b marked complete (owner-confirmed). No scope change (routine).
- **v0.2 (2026-09-13):** Slice a marked complete (owner-confirmed). No scope change (routine).
- **v0.1 (2026-09-12):** Initial slicing from the grilling pass that produced `reporting.md` v0.1.
