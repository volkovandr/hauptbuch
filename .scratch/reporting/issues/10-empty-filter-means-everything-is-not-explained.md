# Report filters: "nothing ticked means everything" is not explained anywhere in the UI

Status: needs-triage
Category: enhancement
Severity: low
Area: Reporting (`fragments/report-filters.html`, `ReportHelpText`)

## What happens

An empty filter section is no filter (`reporting.md` §11a.5), so nothing ticked shows everything.
That rule is reasonable, but nothing on the page says so. An operator who unticks the last box can
easily expect an empty report instead.

## Wanted

State the rule on the Filters group. It is a concept, not an icon label, so per CLAUDE.md §5 it gets
a `.help` marker (with its text in `ReportHelpText`), not a `title` attribute. For example: one
marker on the Filters group heading, "An empty section doesn't filter: nothing ticked includes
everything." Optionally, show a quiet "all" hint on a section with nothing ticked.

Add the marker to `reporting.md` §11a.7's list of help-marker placements.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. If issue 09's exclude mode
lands, the same help text should cover it (an empty exclude also means "everything").
