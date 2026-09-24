# A pie with a single non-zero slice renders empty (legend only)

Status: needs-triage
Category: bug
Severity: medium
Area: Reporting (`analytics` module: `ChartSvgWriter.pie`/`pieSlice`)

## What happens

When exactly one slice carries a positive value, the pie draws nothing. Only the legend appears.

Owner's reproduction: Scope asset, Date range today, Measure closing balance / Base, Account filter
"only amounts booked to" the children of `Cash` (not the parent), renderer Pie.

- **Rows = Account, no columns.** The only row is the collapsed `Cash` parent. A pie makes one pie
  per row, with no column dimension, so there is one slice at 100%. Nothing is drawn.
- **Columns = Account, no rows.** The slices are the top-level accounts, and only `Cash` has a
  value. Again one 100% slice: the legend lists every column label, but no pie is drawn.
- **Columns = Currency** works, because it produces several slices.

## Cause

`pieSlice` draws each slice as an SVG `A` (arc) path from the start angle to the end angle. For a
100% slice, the sweep is 2π, so the arc's start and end points are identical. SVG omits an arc whose
endpoints coincide (SVG 1.1 §F.6.2), so nothing is painted.

## Expected

A single 100% slice renders as a full disc, e.g. a `<circle>` when `sweep ≥ 2π`, or two half-arcs.
Add a `ChartSvgWriterTest` case next to `pieChartRendersOneSlicePerPositiveValue` covering one
positive value (plus zeros and nulls).

Also for triage: the legend lists every column label, including zero-value columns that have no
slice. Consider listing only the labels that actually have a slice.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. The owner's real goal (a pie
of `Cash` split per child account) is still blocked after this fix. Slices come from the column
axis, which can't be expanded in v1 (`reporting.md` §9.1, Q-REP-2). See the comment on
`08-unticked-hierarchy-nodes-still-render-as-rows.md`.

Implemented 2026-09-24 (branch `feat/reporting`), awaiting owner confirmation. A pie with exactly one
positive value draws a full disc (`<circle>`, via `ChartSvgWriter`'s existing `circle` helper)
instead of a 2π arc. The triage point is taken too: the pie legend lists only the columns that have
a slice, each in its slice's own column colour, and it shows even for a single slice (the lone disc
would otherwise name nothing). An all-zero pie is now an empty chart with no legend. Bar/line
legends are unchanged; both legend kinds share one layout helper.
