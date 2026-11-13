# Statements: the statement page is slow — neighbour matches fetch every match on the account

Status: in progress
Category: performance
Area: `StatementMatchRepository.findNeighbourMatches`, `StatementReviewService.balances`
Design: `docs/statements.md` §6.2 (the balance checks); no domain rule changes

## Symptom
Every click on a statement page (accept, move, header edit, open the dock, ...) takes ~2 s on the
Pi, because each one re-renders the whole page (`StatementPageAssembler.addPage`).

## Findings (statement 232, prod, 2026-11-13)
- Postgres logged no statement over 125 ms during a click, and the app's JDBC log shows no burst of
  small queries. The SQL is not the main cost.
- In the JDBC log, `findNeighbourMatches` starts at 17.874 and the next query starts at 19.249:
  ~1.4 s between them. It is the only large fetch on the page: **7,890 rows** (every match on every
  other live statement of the account), each mapped to a `NeighbourMatch` record.
- `StatementBalances.neighbourEffect` multiplies each row's amount by
  `(bankHas ? 1 : 0) - (ledgerHas ? 1 : 0)`. A row where the bank side and the ledger side agree at
  the boundary contributes zero. For statement 232 only **6** of the 7,890 rows disagree at either
  boundary.
- The row count grows with every statement reconciled on the account.

## Accepted solution
Filter in SQL: `findNeighbourMatches` returns only the matches that disagree at the opening or the
closing boundary, using the same predicates as `StatementBalances`:

- opening: `other.period_end < s.period_start` differs from `t.date < s.period_start`
- closing: `other.period_end <= s.period_end` differs from `t.date <= s.period_end`

`StatementBalances` is unchanged (the dropped rows added zero). The repository javadoc names the
coupling.

## Not in this issue (follow-ups)
- Timing the Thymeleaf render (the pause after the last log line).
- `statement_match(posting_id)` index: the `matched_elsewhere` subplan seq-scans `statement_match`
  once per candidate; matters for statements with many unmatched lines (e.g. 277).
- The dock opens through the full page route; a dock-only endpoint is a separate issue.

## Tests
- `sqlLogicTest` (`StatementLedgerBalanceSqlLogicTest`): only boundary-straddling matches are
  returned, for the opening side and the closing side; matches on the same side are not.

## Comments
