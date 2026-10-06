# Statement page lines cannot be sorted by clicking the column headers

Status: open
Category: enhancement
Severity: low
Area: Statements — statement page (`StatementController`, `statement.html`, `StatementMatcher`)

## Symptom

The Matching table shows lines in one fixed order. c1 fixes the default to booking date (then file
order; undated problem lines last), but the operator cannot re-sort.

## Accepted solution (owner decisions)

- Default order: booking date, then file order — done with c1.
- Later: clickable headers, **server-side, no new JS leaf** (CLAUDE.md §1.6). Headers are links
  carrying `?sort=<column>&dir=asc|desc` (optionally an htmx swap of the table); the controller
  re-sorts the review lines; the active header shows the direction.
- Sortable columns: Booking, Amount, Bank text. **Status** sorts by severity (problem, missing,
  ambiguous, overlap, amount differs, wrong account, exact, matched), not by label. **Ledger** is
  not sortable.
- Sort is a view concern only: the matcher's assignment (best tier, closest date, file order) must
  not depend on the display order.
