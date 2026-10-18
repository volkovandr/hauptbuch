# Introduce a caching strategy for database queries

Status: needs-triage
Category: enhancement
Severity: low–medium
Area: cross-cutting (application-wide)

## What prompted this
While diagnosing the slow statements screens (`.scratch/statements/issues/15-matcher-query-slow-on-large-register.md`)
the owner decided that fixing the index and the redundant queries comes first, and that caching
should be handled **separately and globally**, not per query. A search of `.scratch/` and `docs/`
found no issue for a general caching layer: `performance/01` covers only the `settings` read and
defers itself to "when caching is introduced"; `reporting/05` is about query fan-out.

## The shape
Several read paths are recomputed on demand on every request by design (e.g. the statement matcher,
the statements list's per-statement "green" check, report queries, the per-request `settings` read
in `CurrencyPickerAdvice`). CLAUDE.md §4 allows a cache "only when *measured* slow"; this issue is
the placeholder for that decision, to be made once, consistently, rather than ad hoc per query.

## To decide (needs triage / a design pass)
- What is cached: immutable/near-immutable reference data (settings, currencies, account tree),
  per-request memoization, or computed results (matcher review, balances).
- Where it sits (service layer vs repository), the mechanism (Spring Cache + Caffeine vs none), and
  the single invalidation path per cached thing — the ledger is a write-heavy source of truth, so
  stale reads must be impossible for money-critical reads.
- Measure first: record the slow paths and their timings on the Pi before adding anything.

Related: `performance/01` (settings read) should be retired by whatever this introduces.

**Done when:** an agreed strategy is documented, and the measured-slow paths use it with correct
invalidation.

## Comments
Filed 2026-10-18. Not urgent; deliberately separate from the statements performance fix.
