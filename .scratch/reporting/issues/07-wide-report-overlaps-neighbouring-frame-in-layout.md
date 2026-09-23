# A wide report overflows its Frame and draws over the neighbouring Frame in the Layout

Status: needs-triage
Category: bug
Severity: medium
Area: Reporting (`static/css/reports.css` — `.layout-grid`, `.layout-frame`)

## What happens

On the reporting page (`/reports`), the Layout lays Frames out as a CSS grid. When one Frame's report
is wider than its grid cell, for example a Category × Month matrix over a long range, the table spills
out of its Frame and is painted over the next Frame in the same Layout row. Both reports become
unreadable.

## Expected

A report never overlaps another Frame. Too-wide content scrolls horizontally, either:

- inside its own Frame, which keeps the other Frames in place (preferred: the width problem stays
  local to the report that has it), or
- across the whole reporting page.

## Notes for the implementer

`.layout-frame` sets `min-width: 0`, which lets the grid cell shrink below its content, but nothing
clips or scrolls the overflow. `overflow-x: auto` on the Frame (or on a wrapper around its table) is
the likely fix. Check that the `.help` tooltips inside a report table (`.help__text`, anchored from
the right edge) are not clipped by the new scroll container, and that the main page's 1×1 Layout
still looks right.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e.
