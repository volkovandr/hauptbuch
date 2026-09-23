# Settings strip: label the Columns slot "Columns / slices"

Status: needs-triage
Category: enhancement
Severity: low
Area: Reporting (`fragments/report-settings.html`, the Rows & columns group)

## Why

For a pie, the **column** axis supplies the slices, and each **row** gets its own separate pie
(`reporting.md` §10 renderer table). Nothing on the page says so. While trying to build a pie of
`Cash` per account, the owner put Account on rows and got one full-circle pie per row instead of one
pie with a slice per account (see `13-single-slice-pie-renders-empty.md`).

## Wanted (owner)

Rename the Columns label (`settings-columns`, and its nested slot if it has a label) to
**"Columns / slices"**.

## Notes for triage

- The rename could go further: Line and Bar charts use columns as the x-axis, and rows as one small
  chart each. A `.help` marker on the Rows & columns controls could explain what each axis does per
  renderer (CLAUDE.md §5: a concept gets `.help`, not `title`). The owner's request is only the
  label. Take the marker only if triage wants it.
- If `12-regroup-the-report-settings-strip.md` lands first, the label lives in the new
  "Dimensions & measures" group. Doing both in one pass is cheapest.
- Update any integration test that asserts on the label text.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e.
