# Introduce a caching strategy for database queries

Status: in progress
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

## Accepted solution (owner, 2026-10-21)
**Scope: reference data only.** Cached, each evicted by the single repository writer that changes it
(all writes to these tables are in the repository classes below — verified by grep):

| Cache | Cached read | Evicted by |
|-------|-------------|------------|
| settings | `SettingsRepository.load()` (never `loadAi()` — it holds the API key) | every `SettingsRepository.update*` |
| currencies, currencyOptions | `CurrencyRepository.findAll`, `CurrencyOptionRepository.findAll` | `CurrencyRepository.insert` |
| countries | `CountryRepository.findAll` | nothing writes it |
| accountTree | `AccountRepository.findLiveByTypesWithDepth(types)` (categories are accounts) | `insert`, `softDelete`, `updateNameAndHue`, `updateShowOnMainPage`, `updateParent`, `updateDetection`, `close`, `reopen` |
| tagTree, tagLabels | `TagRepository.findLiveWithDepth`, `TagReadRepository.liveTagLabels` | `TagRepository.insert` |
| payees, payeeFilterOptions | `PayeeRepository.findAllLive`, `findFilterOptions` | `PayeeRepository.insert`, `rename` |
| people | `PersonRepository.findAllLive` | `PersonRepository.insert`, `updateName`, `softDelete`, `revive` |

**Not cached:** anything derived from postings/transactions (balances, register rows, reports,
pinned balances, pending-review count, exchange-rate lookups, `findPostedAccountIds`) and the
statement matcher / list-page review (`statements/16` problem 2 — needs a computed-result cache with
eviction on every ledger write; measure first, separate decision).

**Mechanism:**
- Spring cache abstraction + Caffeine; `@Cacheable` / `@CacheEvict(allEntries = true)` on the
  **repository** methods, read and writer side by side. Cache names are constants in the `shared`
  kernel module (`CacheNames`), so a writer in one module can evict a cache another module reads.
- One `CacheManager` bean in `shared`, wrapped in `TransactionAwareCacheManagerProxy` so evictions
  and puts happen after commit (a rolled-back write evicts nothing; a concurrent reader cannot
  refill a stale value before the commit).
- No TTL is needed for correctness (every write goes through the app); a long safety-net expiry
  (`hauptbuch.cache.expire-after-write`, default 1 h) covers out-of-band edits in the database.
- Reads **inside a transaction skip the cache** (`condition = CacheNames.OUTSIDE_TRANSACTION`): the
  eviction waits for the commit, so a cached read there would miss the transaction's own writes.
  Controller/picker reads run outside transactions and are cached. (Added after code review.)
- A reader that loaded old rows just before a commit can put them back just after the eviction; it
  needs two concurrent requests on a single-user app and ends at the safety-net expiry. Accepted.
- Cached values are immutable (`List.copyOf` at the cached method).
- `hauptbuch.cache.enabled=false` swaps in a no-op manager; every test suite runs with it off
  (rolled-back tests would otherwise leak cached data between tests). Only the dedicated cache
  integration test turns it on.
- Tests per cache: read (populates) → change the row with raw JDBC (read still returns the old
  value: proves it is cached) → call the repository's writer → read shows the new state.
- Retires `performance/01` (settings read per request).

**Done when:** the strategy above is in CLAUDE.md §4 / tech-stack, each listed cache has its
read-then-write test, and `./gradlew check` is green.

## Comments
Filed 2026-10-18. Not urgent; deliberately separate from the statements performance fix.
