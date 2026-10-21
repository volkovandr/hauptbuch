# Statements: candidate query still scans the whole register, and the list page runs it once per statement

Status: open
Category: bug (performance)
Severity: high — the statements list page runs one multi-second query per statement
Area: `statements` — `StatementMatchRepository` (`CANDIDATES_HEAD`), `StatementController.list`, `StatementReviewService.green(rows)`
Follow-up to: `15-matcher-query-slow-on-large-register.md` (resolved; its index and `lineOf` fixes are in, this is what remained)

## Symptom
Owner (Raspberry Pi, ~28k transactions): after issue 15 working inside one statement is faster but
still not comfortable, and opening the **statements list** page logs one very slow candidate query
per statement — e.g. `duration: 9681 ms` for `$1 = 7` (a statement of 33 lines, window 7 days
before / 1 day after). V37 (`transaction_date_idx`) is applied in production.

## Root cause
Two separate problems.

1. **The candidate query does not use `transaction_date_idx`.** Reproduced on a scratch Postgres 16
   with ~28k synthetic transactions (25 statements × 15 lines, real migrations). The plan seq-scans
   all of `transaction` and runs the whole `join lateral` posting lookup once **per transaction**
   (`loops=28000`: posting index scan, hash join to `account`, sort, group) *before* joining to the
   statement lines, then discards nearly everything with the date-window join filter (31k rows
   removed). The lateral depends only on `t`, so the planner builds `t ⋈ lg` first (estimating `lg`
   at 1 row) and joins the lines last; the index can only help if the window is applied first. Cost
   is proportional to the register, not the statement: ~150 ms on a fast machine, seconds on the Pi.
   The owner's production plan also shows `loops=27643` on the same lines — the same shape.

   Experiment (scratch DB only): evaluating the window per line through a non-flattenable subquery
   (`join lateral (select … from transaction where deleted_at is null and date between … offset 0) t
   on true`) makes the planner use the date index (~138 transactions per line) and drops execution
   from ~149 ms to ~4.5 ms. A plain `join lateral` without `offset 0` is flattened back into the bad
   plan. The seed produced no result rows, so result equality was **not** verified — the real fix
   needs the `sqlLogicTest` comparison.

2. **The list page runs a full review per statement.** `StatementController.list` →
   `StatementReviewService.green(rows)` → `isGreen(id)` per row → `review(id)` (`findCandidates`,
   `findMatches`, `findExtras`) plus `balances(id)` (`findNeighbourMatches`, two `ledgerBalance`
   calls). N statements ⇒ N × (the slow query + ~5 more). Issue 15 noted this but only changed
   `lineOf`. The cost of `findExtras`/`ledgerBalance` on the Pi is not yet measured.

## Decisions (owner, 2026-10-21)
- **Problem 2 is handled by the global caching work** (`.scratch/performance/issues/02-introduce-database-query-caching.md`,
  option "2c"): the list page's per-statement "green" check is the first measured-slow path for that
  strategy. Caching goes in **first**; no separate list-page rewrite (batched query / dropping the
  green marker) for now.
- Caching only removes repeat cost; the first, cold computation stays as slow as problem 1 makes it.

## Open — needs the owner's decision
- **Problem 1 (query plan fix)** is proposed, not yet accepted: restructure the window join so it is
  evaluated per line (index used), with a `sqlLogicTest` scenario asserting the rewritten query
  returns exactly the old query's rows (including line-scoped and rivals variants, which share
  `CANDIDATES_HEAD`). Recommended to do it alongside or before caching, since a cache still pays the
  slow cold path after every ledger edit.

**Done when:** opening the statements list and a statement page on the Pi's data completes well
under a second, the candidate query's plan uses `transaction_date_idx` on a register of that size,
and `./gradlew check` is green.

## Comments
Filed 2026-10-21 from the owner's slow-query log, a production `EXPLAIN` (`loops=27643`) and a local
reproduction. Nothing is implemented yet.
