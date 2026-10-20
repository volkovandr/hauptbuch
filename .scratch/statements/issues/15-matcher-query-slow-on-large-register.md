# Statements screen freezes: the candidate query takes seconds on a 27k-transaction register

Status: resolved
Category: bug (performance)
Severity: high — nearly every action on the statements screens blocks for several seconds
Area: `statements` — `StatementMatchRepository.findCandidates`, `StatementMatchService.lineOf`

## Symptom
Owner (Raspberry Pi, ~10 small statements of 10–20 lines, register of ~27k transactions): clicking
more or less any button on the statements screens freezes the app for several seconds with
PostgreSQL at 100 % CPU. "Amend" → edit → "Save and Match" on one mismatched-amount line took over
10 s. The Postgres log shows `findCandidates` (the `with scored as (…)` query) at 6505 ms for one
execution with `$1 = 7`.

## Root cause
Two compounding problems.

1. **No index on `transaction(date)`.** The query's core join is the non-equi range
   `t.date between l.booking_date - sp.window_days_before and l.booking_date + sp.window_days_after`.
   The only indexes on `transaction` are the PK and the recurring-occurrence one, so each line is
   matched against the whole table. Measured on the Pi, same query, JIT off:

   | Setup | Query time |
   |-------|-----------:|
   | no index, JIT off | ~5700 ms |
   | index on `transaction(date)` | ~260 ms |
   | index on `transaction(date)`, JIT off | ~45 ms |

   (JIT is a Postgres server setting and is out of scope for this codebase — see Decisions.)

2. **The matcher is re-run far more often than needed.** `StatementReviewService.review()` runs
   `findCandidates` for the *whole statement* each time, and it is called for single-line work:
   `StatementMatchService.lineOf(...)` (used by accept, reject-candidate, unmatch and every dock
   action in `StatementDockService`, plus `StatementCrossCurrencyService`) runs a full review only
   to pick one line out of it. One Amend/Save-and-Match click therefore pays for several full
   candidate queries, and the statements list page runs one per statement (`green(rows)`).

## Accepted solution (owner, 2026-10-18)
1. **Add a Flyway migration** creating an index on `transaction (date)` (partial on
   `deleted_at is null`, as the candidate query and the other statement queries filter on it). Add a
   migration test asserting the index exists.
2. **Stop running the whole-statement matcher for single-line lookups.** Give `lineOf` a
   line-scoped path: a candidate query restricted to one `statement_line_id` (same SQL, extra
   predicate) so the work is proportional to one line's window, not the statement. The full
   `review()` stays for the page render and `acceptAllExact`. The line-scoped result must be
   identical to the corresponding line of the full review (cover with a `sqlLogicTest` scenario
   comparing the two).

## Decisions
- Query-result caching is **not** part of this fix; it is a separate, global piece of work
  (`.scratch/performance/issues/02-introduce-database-query-caching.md`).
- Disabling JIT is a Pi/Postgres configuration matter and stays **out of this codebase**.

**Done when:** on the Pi's data, a candidate query and the Amend → Save and Match flow complete
well under a second, `./gradlew check` is green, and `lineOf` no longer triggers a whole-statement
candidate query.

## Comments
Filed 2026-10-18 from the owner's Pi timings and Postgres slow-query log.
