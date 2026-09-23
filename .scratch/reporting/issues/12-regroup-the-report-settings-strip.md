# Regroup the report settings strip: one "Dimensions & measures" group, Date range before Filters, Renderer under Display

Status: needs-triage
Category: enhancement
Severity: low
Area: Reporting (`fragments/report-settings.html`, `static/css/reports.css`; `docs/reporting.md` §11a.2)

## Today

The settings strip (`reporting.md` §11a.2) has six collapsed `<details>` groups in this order: Rows &
columns, Measures, Scope, Filters, Date range, Display. The Renderer buttons sit outside the groups
and are always visible.

## Wanted (owner)

| Group | Holds |
|-------|-------|
| **Dimensions & measures** | today's Rows & columns, Measures and Scope, together |
| **Date range** | unchanged, now before Filters |
| **Filters** | unchanged |
| **Display** | today's Display toggles, plus the **Renderer** buttons |

So the order becomes: Dimensions & measures → Date range → Filters → Display.

## Notes for the implementer

- **Keep the three sections separate inside the merged group.** Each has its own sub-heading and its
  own form. Per §11a.3, Rows & columns is live, while Measures and Scope's account types wait for their
  own **Apply**. Merging them into one group must not merge their submit behaviour.
- **Move the help markers.** `MEASURE_KIND` and `SCOPE` sit on today's group `<summary>` elements.
  They move to the matching sub-headings.
- **The Renderer goes into Display with everything attached to it:** the trend-line option shown for
  Line, and the pie refusal message (§7.4). Once the Renderer sits in a collapsed group, the current
  renderer is no longer visible at a glance. Decide at triage whether the Display `<summary>` should
  name it (e.g. "Display · Table").
- **Opened groups stay open.** Each group's open state survives an htmx re-render because the
  `<details>` elements are never swapped (commit 9343a18). Check that the merged group keeps this.
- **Docs.** Update the §11a.2 table and the Renderer paragraph below it in `reporting.md`.
- **Tests.** Adjust any integration tests that assert on group names or where the Renderer control
  renders.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e.
