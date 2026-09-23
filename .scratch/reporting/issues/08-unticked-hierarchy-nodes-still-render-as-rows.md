# Category/Account filter: unticked nodes and their untouched parents still render as rows

Status: needs-triage
Category: bug
Severity: medium
Area: Reporting (`analytics` module: `ReportQueryRepository` candidate queries, `ReportGridBuilder`, `RowColumnSuppression`; `docs/reporting.md` §6.3, §7.3)

## What happens

Two related cases with Account (or Category) as a dimension and a filter on the same field:

1. **Unticked sibling.** Untick one account in the Account filter. Its row (or column) still
   appears, with blank cells. It disappears only when *Suppress empty rows/columns* is ticked. This
   is because the candidate queries (`topLevelAccounts`, `childAccountCandidates`, …) list every
   node regardless of filters, and suppression is the only thing that hides a filtered-out one
   (`reporting.md` §7.3).
2. **Parent of ticked children.** The owner wants to see all Cash accounts but *not* the `Cash`
   parent row. They tick only the child accounts. The `Cash` parent still renders, with numbers,
   because a parent row is the subtotal of its (ticked) children. Suppression can't hide it because
   it isn't empty.

## Expected (owner)

Anything not selected in the filter does not appear on the axis at all, independent of the
suppression toggle. Selecting only the children of `Cash` shows just those children, without the
`Cash` parent row.

## Open questions for triage

- **Case 1** reads as a plain bug: when a dimension has a filter on its own field, a node outside the
  filter's selection (and not an ancestor of a selected node) should not become a candidate at all.
  Suppression stays for genuinely zero-activity nodes.
- **Case 2** is a design change. §6.3 says a ticked node includes its subtree, and §9 renders a
  hierarchy as a tree, so the parent currently shows up as the path to its ticked children. Dropping
  it means the ticked children are promoted to top-level rows when their parent isn't itself ticked.
  Is that the rule? What happens with a deeper tree (tick a grandchild only: are the intermediate
  parents dropped too)? How does it interact with *group-header parents* mode and with `auto`
  expansion (§9.2)? Needs an owner decision and a line in `reporting.md` before implementing.
- The filter's **reading switch** ("only transactions touching …" vs "only amounts booked to …",
  §6.2) matters here: under *touching*, other accounts on the same transactions legitimately carry
  numbers. The expected behaviour above probably applies to *booked to* only. Confirm.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. Related:
`09-exclude-mode-for-hierarchy-filters.md`.

2026-09-23, owner use case that depends on case 2: a pie of `Cash` split per currency account
(closing balance, Base). Pie slices come from the column axis, and columns can't be expanded in v1
(`reporting.md` §9.1, Q-REP-2), so Account on columns only ever gives top-level slices, here just
`Cash`. If ticking only the children of `Cash` made them the top-level axis nodes, this pie would
work without column expansion, and the table would stop needing save-then-expand. Weigh this when
deciding case 2. See also `13-single-slice-pie-renders-empty.md`.
