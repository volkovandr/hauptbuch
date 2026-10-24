# Statements: the list's green tick is computed on demand ("Check" button), not on every page load

Status: in progress
Category: bug (performance) / UX
Severity: high — with 50+ statements the list page takes over 30 seconds to open
Area: `statements` — `StatementController.list`, `StatementReviewService.green(rows)`, `statements.html`
Follow-up to: `16-candidate-query-ignores-date-index-and-list-page-reviews-every-statement.md` (problem 2)

## Symptom
Owner: the statements list takes 30+ seconds to load once there are 50+ statements. Each row's
Match tick is a full review plus both balance checks (`green(rows)` → `isGreen(id)` per row), run
synchronously before the page renders.

## Root cause
Issue 16 problem 2, left open pending the caching work: the list renders a per-statement "green"
verdict, and the verdict costs a full live review. Cost is N × review, paid on every page load.

## Accepted solution (owner, 2026-10-24)
- The list no longer computes anything per statement. Each row's Match cell holds a small **Check**
  button.
- Clicking it (htmx `hx-get`, swapping that one cell) runs the existing green rule for that
  statement only and shows **✓** when green, **✗ plus the reason** when not. The result is not
  stored: a refresh brings the buttons back.
- The reason lists what fails, from the same rule as green (statements.md §6.5): no lines; N of M
  lines not matched; N extras; opening / closing balance off by X. One source of truth — green is
  "no problems".
- **Check all** (toolbar): reloads the list with every cell in a "checking…" state; each cell fires
  its own check request on load, and `hx-sync` on the shared `<tbody>` queues them so the server
  works through the statements one at a time. Ticks and Xes appear as each answer lands. Pure
  htmx — no new JS leaf. Respects the account filter.
- The single-statement page is unchanged (one statement, delay not noticed).

## Done when
The list opens instantly regardless of the number of statements; Check shows ✓ / ✗ with a reason
for one statement; Check all fills the column progressively; `./gradlew check` is green.

## Comments
Filed 2026-10-24 from the owner's report. `performance/02` (caching) is no longer needed for this
page.
