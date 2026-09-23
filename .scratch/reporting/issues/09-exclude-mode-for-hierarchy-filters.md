# "All except" filters: an exclude mode for the Category/Account/Tag filters

Status: needs-triage
Category: enhancement
Severity: low
Area: Reporting (`analytics` module: `ReportFilter`/`FilterField` operators, filter-predicate compiler, `fragments/report-filters.html`, `filter-groups.js` node mode; `docs/reporting.md` §6.3, §11a.5)

## Wanted (owner)

Reports of the shape "all expenses except X", e.g. every expense category except `Rent`. Today this
works only by ticking every category except the unwanted one. That is tedious, and it also silently
drops categories created later (the reason §11a.5 stores ticked *nodes* rather than leaves in the
first place).

An exclude ("is not one of") mode on a filter section would express this directly: tick `Rent`,
switch the section to *exclude*, done.

## Notes for triage

- `reporting.md` §6.3 offers only `is one of` for Category/Account/Tag. This needs a doc decision
  first: an `is not one of` operator, with the same subtree rule (excluding `Food` excludes its whole
  subtree).
- The compiler already builds a subtree `IS_ONE_OF` predicate (`accountHierarchyPredicate`,
  `tagPredicate`). The exclude form is its negation. For tags, decide what "not tagged Trips" means
  for untagged postings (included, presumably).
- UI: a per-section include/exclude switch next to the existing reading switch. It would be the one
  filter control that exists on Category/Account/Tag and could reasonably extend to Payee, Person
  and Currency later. Keep `filter-groups.js` changes minimal, since it is a sanctioned leaf
  (CLAUDE.md §1.6).
- Round-trip through `ReportSpecJson`/`ReportSpecQueryString`, defaulting a missing key to include.
- Should also drive row/column visibility the way issue 08 wants for include mode (excluded nodes
  don't render).

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. Related:
`08-unticked-hierarchy-nodes-still-render-as-rows.md`.
