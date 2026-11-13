# Statements: index `statement_match(posting_id)`

Status: resolved
Category: performance
Area: Flyway migration V42; `StatementMatchRepository` (`matched_elsewhere`, `findNeighbourMatches`)
Design: none (no domain rule)

## Symptom / finding
`statement_match` is indexed on `(statement_line_id)` and `(statement_id, posting_id)` only. Two
queries look matches up by `posting_id` alone:

- the candidate query's `matched_elsewhere` subquery (`posting_id = lg.posting_id and statement_id <>
  s.statement_id`) — on prod (statement 277, 9,114 rows) it ran as a seq scan of the table once per
  candidate, ~300 of the query's 433 ms;
- `findNeighbourMatches`, which joins `posting p on p.posting_id = m.posting_id`.

## Owner's decision
Add the index even though the owner's trial of it on the Pi showed no visible difference on
statement 232 (which has one unmatched line, so the subquery barely runs). It is cheap, the table
only grows, and statements with many unmatched lines are where it matters.

## Accepted solution
`V42__statement_match_posting_idx.sql`: `create index statement_match_posting_id_idx on
statement_match (posting_id)`.

## Tests
- `integrationTest` (`SchemaMigrationIntegrationTest`): the index exists on `posting_id`.

## Comments
- 2026-11-13: owner verified on the Pi (migration applies, index in use).
