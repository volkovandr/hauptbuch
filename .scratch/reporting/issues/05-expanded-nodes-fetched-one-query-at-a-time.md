# Each expanded node costs its own queries (N+1): measure before fixing

Status: needs-info
Category: enhancement
Severity: low
Area: Reporting (`analytics` module: `ReportEngine`, `ReportDataFetcher`)

Expanding tree rows (stage e, `reporting.md` §9.1) adds database round-trips **per expanded node**
rather than one batched query per level:

- `ReportEngine.childCandidatesByParentKey`: one child-candidates query **per expanded key**.
- `ReportDataFetcher.mergeChildTurnover`: one child-turnover query **per expanded key × per leg**
  used by the spec's measures.
- `ReportDataFetcher`'s closing-balance counterpart: one child-balance query **per expanded key × per
  Date bucket** (the as-of date differs per bucket).
- `ReportEngine.build`: each **expanded Date row** re-runs the whole `fetchGridData` over that
  bucket's range at day granularity. That includes all of the above, repeated once per expanded
  Date bucket.

So a Report with 10 expanded categories, two legs and a 12-month closing-balance measure issues
roughly 10 + 20 + 120 queries per render. Collapsed nodes whose parent has since been collapsed are
also kept in `report.expanded_node_keys` and still fetched ("orphaned" nested keys; nothing prunes
them).

## Why it is not fixed yet

CLAUDE.md §4: *add a cache only when measured slow*. This is a single-user app, and fan-out is
bounded by what the operator expands by hand. No slowness has been observed yet, and batching would
turn every child query into an `= any(:parentIds)` variant with a parent-id column carried back, which
makes the SQL a lot harder to read.

## What would move it forward (needs-info)

A real measurement on the Pi with production-sized data: render time of a saved Report with many
nodes expanded, closing-balance measure, and a 12+ month range, compared with the same Report
collapsed. If the difference is noticeable (say well over ~1 s), the fixes in order of payoff are:

1. **Prune orphaned keys**: when a node collapses, drop the keys of its descendants from
   `expanded_node_keys` (cheap, no SQL change).
2. **Batch child candidates**: one query for all expanded keys of a dimension.
3. **Batch child turnover/balances** per leg or per bucket, keyed back by parent.

## Comments

Filed 2026-09-23 from the stage e2/e3 `/code-review` notes (session-local
`.scratch/.workpackages-e`). Parked as "measure first", not scheduled.
