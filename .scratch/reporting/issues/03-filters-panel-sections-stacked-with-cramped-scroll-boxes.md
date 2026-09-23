# Filters panel stacks every section vertically; the big lists get a cramped 12rem scroll box

Status: ready-for-agent
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module, `fragments/report-filters.html`, `static/css/reports.css`)

The settings strip's **Filters** group (`reporting.md` §11a.5) renders its nine fixed sections —
Category, Account, Tag, Payee, Person, Currency, Account type, reconciliation, note text — one under
another, full width. Sizes differ wildly: Category, Account and Payee are long lists, while
reconciliation and note text are a line or two. The long lists are capped at `max-height: 12rem`
with their own vertical scroll (`.report-settings__filter-tree`, `.report-settings__filter-options`).
The result is a very tall panel in which the lists you actually need to scan are the smallest
windows on the page: every tick is a scroll inside a scroll.

## Wanted

- Lay the sections out **side by side in columns**: two or three, chosen by screen width (CSS grid
  `repeat(auto-fill, minmax(…, 1fr))`, not media-query breakpoints or JS).
- Give the big hierarchy lists **about 3× today's height** (≈ 36rem) so most of a category/account
  tree is visible without scrolling. The panel still gets shorter overall, because the sections sit
  next to each other instead of stacking.
- Small sections (reconciliation, note text, Currency, Account type, Person) should not each reserve
  a big-list-sized cell. Let them share a column, or keep them compact, so the grid doesn't open up
  large empty areas.
- Must still work at phone width (one column) and keep each section's own Apply button and reading
  switch where they are now.

## Constraints

- CSS and template structure only. No new JS: `filter-groups.js` stays as it is (CLAUDE.md §1.6).
- The markup order of the sections should stay §11a.5's fixed order, so keyboard/tab order stays
  predictable even when the columns move sections visually.
- If the final layout differs from how §11a.5 describes it, update the doc in the same change.

## Done when

On a desktop-width window, the Filters group shows the sections in 2–3 columns, with the Category,
Account and Payee lists visibly taller than today. On a narrow window it falls back to one column.
No section loses its Apply or reading-switch control.

## Comments

Filed 2026-09-23 from the owner's review findings on reporting stage e (session-local
`.scratch/.workpackages-e`). Triaged as its own slice after stage e5, not folded into e5: it is a
self-contained layout change to §11a.5 that deserves its own UI review.
