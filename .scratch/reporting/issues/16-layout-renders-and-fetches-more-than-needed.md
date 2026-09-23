# Layout pages render every Frame sequentially, and the editor re-renders and re-fetches more than it needs

Status: needs-triage
Category: enhancement
Severity: low
Area: Reporting (`analytics` module: `ReportsLayoutController`, `MainFrameController`, `PresetRendering`, `LayoutRepository`; `fragments/layout-config.html`, `fragments/frame.html`)

From a performance code-review pass during stage d2, re-checked against the code on 2026-09-23. All
items are still present. None is measured slow yet (`reporting.md` §15: optimise with a number
attached), but the first two scale with the number of Frames and run on the Pi.

## Worth doing

1. **Every Frame renders sequentially inside one request.** `ReportsLayoutController.frameViews`
   calls `PresetRendering.renderFrame` → `reportEngine.render` once per grid cell, in a loop, so
   `/reports` takes as long as all its reports together. An htmx-idiomatic fix: render each Frame's
   shell immediately and load its report with `hx-get … hx-trigger="load"`, so Frames arrive
   independently and a slow one doesn't hold up the page. This also gives a natural place for a
   per-Frame error.
2. **The editor re-renders every Frame on each rows/columns change.** `resize()` calls
   `populateLayout`, which re-runs every Frame's report (N finds + N renders), even though growing
   a 2×2 to 2×3 only adds two empty Frames. Item 1's lazy loading would largely fix this too.
3. **`save()` renders a response nobody sees.** It sets `HX-Redirect` and then still calls
   `populateLayout`, paying the full N-Frame render for a body the browser discards. Return an empty
   body after the redirect header.

## Nits (take only alongside the above)

4. `frameViews` → `resolve` calls `reportService.find(reportId)` once per Frame, although
   `reportService.list()` was loaded moments earlier in `populateLayout`. Resolve from that list.
5. `MainFrameController.renderPicker` calls `reportService.list()`, and then `PresetRendering.isKnown`
   calls `reportService.find` again for the same report.
6. `LayoutRepository.save` inserts Frames one statement at a time. Frame counts are tiny and
   hand-configured, so a batch insert is optional.

## Comments

Filed 2026-09-23 from the leftover `.scratch/.code-review-to-review` notes (stage d2 review, performance
agent).
