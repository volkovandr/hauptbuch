# Save layout: invalid input and a vanished Report show only the generic error toast

Status: needs-triage
Category: bug
Severity: low
Area: Reporting (`analytics` module: `ReportsLayoutController.save`, `LayoutService.saveReportsLayout`; `fragments/layout-config.html`)

## What happens

Two failure paths on `POST /reports/layout` ("Save layout") end in the global htmx error boundary
(`GlobalHtmxErrorAdvice`). It shows "Something went wrong and your change was not saved" and logs
the failure at ERROR, as if it were unexpected:

1. **Empty Rows or Columns field.** `save()` binds `@RequestParam int rowCount/columnCount` with no
   guard, so a blank field throws `MethodArgumentTypeMismatchException`. `resize()` clamps the same
   input, and `LayoutService.saveReportsLayout` rejects values below 1 with an
   `IllegalArgumentException`, but a blank value fails at binding, before either check runs.
2. **A Frame names a Report deleted since the editor opened** (e.g. deleted in another tab).
   `LayoutService.requireKnownOrEmpty` throws `IllegalArgumentException("No such report: …")`, and
   nothing translates it into a message for the operator.

Both are *expected* failures (CLAUDE.md §5 logging ladder: WARN, not ERROR), and the operator can't
tell what to fix.

## Expected

Validation handled inline, as other screens do: the layout editor re-renders with a message next to
the offending field or Frame. For a blank dimension, either treat it like `resize()` does (clamp to 1)
or show "Rows must be at least 1". A vanished Report could simply render as an empty Frame with a
note.

## Comments

Filed 2026-09-23 from the leftover `.scratch/.code-review-to-review` notes (stage d2 review,
correctness agent, findings 1 and 3). Finding 4 of that pass ("Save layout" gives no feedback when
the request fails) is resolved: the global htmx error boundary (issue 10, and the `HX-Reselect`
fix in 22f7993) now shows a toast.
