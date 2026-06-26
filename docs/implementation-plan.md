# Hauptbuch — Implementation Plan

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.2
**Date:** 2026-06-25
**Owner:** volkovandr
**Companion to:** `requirements.md` (v0.4),
`tech-stack.md` (v0.1),
`data-model.md` (v0.3),
`ui-transaction-register.md` (v0.2),
`ui-receipt-processing.md` (v0.2)

> This document records the **build sequence** — the order in which the system is implemented, what
> each stage delivers, and *why that order*, in keeping with the house rule that the *why* must
> survive long after the *what* is code.
>
> Scope note: stages 1–6 are specified in detail because their shape is forced by the data model and
> the legibility constraint. **Stages 7 onward are deliberately rough** — once the UI is live at
> stage 6, priorities will shift, and pinning detail now would be premature (the owner's standing
> position). §14 is an **unordered backlog inventory** — nothing in it is sequenced or staged yet;
> priority is set during implementation, once the system is in use.
>
> Several decisions in §1 are leans pending confirmation; they are listed in §16 as «A» working
> assumptions that can be overturned.

**Changelog**
- **v0.2 (2026-06-25):** Multi-currency is now **fully live from stage 3** (no single-currency early
  phase) — only the ECB feed automation and held-balance revaluation defer, as feed/reporting
  concerns. Base currency moved out of config **into a root `settings` entity in the database**,
  write-once, required before any transaction; the entity is born at stage 3 (the engine needs it),
  UI at stage 5, and is sketched here for ratification into the data-model doc (which currently has
  no root settings entity). §14 reduced from a proposed ordering to a flat, unordered inventory — no
  stages assigned to backlog features.
- **v0.1 (2026-06-25):** Initial plan. 13 staged increments + a backlog; cross-cutting decisions
  settled up front (module-first from day one; three-tier test strategy with container reuse).
  Reframed the stage-3 "CRUD service" as an invariant-upholding domain-ops layer. Folded categories
  + opening balances into stage 6.

---

## 0. The governing constraint, applied to sequencing

From the tech-stack doc's §0: *the codebase must stay navigable and correct without AI-agent
assistance.* Applied to **build order**, that yields three rules every stage obeys:

1. **Every stage ends green and demoable.** The build — including `ApplicationModules.verify()` and
   all three test suites — is passing at each stage boundary, and there is something runnable to look
   at. No stage leaves a half-wired module the owner must hold in their head across a gap.
2. **Vertical slices, not horizontal layers.** Each stage cuts through model → migration → repository
   → service → (where relevant) UI for one capability, so the slice is comprehensible end-to-end.
   This is also what Spring Modulith's module-first packaging demands (tech-stack §3).
3. **The engine before its conveniences.** The uniform posting model (the hard, correctness-critical
   part) is built and tested before any display/entry sugar is layered on it — exactly the
   model-vs-presentation split the register and receipt docs already draw.

---

## 1. Cross-cutting decisions established before stage 1

These are committed **up front** because retrofitting them is expensive (Modulith packaging) or
because they shape the schema (currency, settings).

### 1.1 Module-first packaging from day one (Modulith)
Adopt the tech-stack §3.1 module map (`ledger`, `accounts`, `categories`, `debts`, `receipts`,
`statements`, `budgets`, `recurring`, `analytics`, `forecasting`, `importer`, `operations`, `mcp`)
as the package layout **from the skeleton**, even though most modules are empty stubs for a long
time. `ApplicationModules.of(App.class).verify()` is a test from stage 2 — the boundaries have teeth
before there is anything to violate.

### 1.2 Multi-currency is fully live from stage 3
There is no single-currency early phase. The posting engine handles cross-currency from the start:
the conditional sum-to-zero invariant (both branches), frozen `base_amount` on genuinely
two-currency postings, and the single signed `FX gain/loss` leaf as the residual that balances a
non-par conversion (data-model §6.3/§6.4 — *not* a new mechanism, just the cross-currency path). The
`exchange_rate` table exists from stage 3 with manual rows and the carry-forward `rate_as_of` lookup.

Two currency-related pieces arrive **later, with the reports/integration that consume them** — this
is *not* deferring multi-currency, it is building each report when it is built:
- **ECB rate-feed automation** — an integration; until it lands, rates are entered manually (which is
  all a booked conversion needs anyway: the two real amounts are the source of truth, frozen).
- **Held-balance revaluation** (`native × rate@D` mark-to-market for net worth and the balance
  timeline) — a valuation query that appears with net-worth reporting (§14).

### 1.3 Base currency and the root `settings` entity
Base currency lives **in the database**, not config — in the single-row root `settings` entity, now
ratified into the data-model doc (§3.8). It is **write-once**: required before any transaction is
entered, and immutable thereafter (changing it would invalidate every frozen `base_amount` and the
whole FX interpretation). The engine refuses to record a transaction while base currency is unset.

Because a frozen `base_amount` is denominated in the base currency, the **engine depends on this
entity**, so it is born at **stage 3** (UI at stage 5 — the same early-table / later-UI split as
`currency`). The canonical schema is in **data-model §3.8**; reproduced here for convenience:

```sql
create table settings (
  settings_id   smallint primary key default 1 check (settings_id = 1),  -- single-row guard
  base_currency text references currency(currency_code),  -- write-once; NULL until first-run set
  display_name  text                                      -- the "Hello, %name%" greeting
  -- future global settings land here as typed columns (legibility > key/value bag)
);
```

Write-once is enforced at the application layer (the `settings` service refuses to overwrite a
non-null `base_currency`); a constraint trigger is optional, the same stance as the sum-to-zero
invariant (data-model T-DM-2).

### 1.4 The core service is a domain-operations layer, not CRUD
Recording a transaction creates *balanced postings* and must uphold sum-to-zero and leaves-only; it
is not row CRUD. The `ledger` service exposes invariant-upholding operations (`recordTransaction`,
`voidTransaction`, `editTransaction` with re-threading) — the same first-class-operations principle
as ARCH-12 / the `operations` module, in miniature. Generic per-table CRUD exists only for true
reference data (payees, tags, account definitions).

### 1.5 Test strategy — three suites, real Postgres, reused containers
Per the owner's note, via the Gradle JVM Test Suite plugin (see §15 for the full rationale):
`test` (unit, Mockito, no container, fast), `integrationTest` (Flyway + repositories vs
Testcontainers), and a logic suite (SQL-resident logic vs Testcontainers — same setup, different
intent). **Container reuse** (singleton / `testcontainers.reuse.enable=true`) keeps the
Postgres-backed suites fast enough for a tight TDD loop.

---

## 2. The staged plan

### Stage 1 — Project setup
**Goal:** a repository a stranger (or the owner, months later) can orient in.
- GitHub repo; license; `README.md`; `CLAUDE.md` (state the Modulith rule with teeth — "boundaries
  are enforced by `ApplicationModules.verify()`; run the module test after changes" — per tech-stack
  §3, not a prose description the agent may ignore).
- Move the design docs into the repo (`/docs`), so the *why* travels with the code.
- `.editorconfig`, formatting/lint config, `.gitignore`.

**Done when:** repo cloneable, docs in place, contribution conventions written down.

### Stage 2 — Skeleton
**Goal:** an empty but *enforced* modular monolith that boots and tests green.
- Spring Initializr (Java, Spring Boot 3.2+, Web, Thymeleaf, JdbcClient, Flyway, Testcontainers,
  Validation).
- Module-first packages from §1.1 (empty stubs).
- Spring Modulith dependency + the `verify()` test (§1.1).
- Gradle test suites scaffolded (§1.5): `test`, `integrationTest`, logic suite, each with a sample
  green test; Testcontainers Postgres + reuse wired.
- Money type chosen (T1: Joda-Money vs JSR-354 — pick now, low-risk either way) and a thin wrapper.
- Local `docker-compose` for a dev Postgres (app stays on the JVM for now).

**Done when:** `./gradlew build` runs all three suites and the module-verify test, all green; app
boots against the compose Postgres.

### Stage 3 — The transaction core (the engine, fully multi-currency)
**Goal:** the uniform posting model, correct and tested, with **no UI**.
- **Settings entity** (§1.3) + migration: single-row `settings`, write-once `base_currency`. The
  engine treats "base currency set" as a precondition for recording transactions. (Tests set it via
  fixtures; the UI to set it is stage 5.)
- **Migrations (Flyway):** `currency` (seed the currencies actually used — data-model §3.1),
  `account`, `transaction`, `posting`, `exchange_rate`, `payee`. Seed the **system accounts**
  (Opening Balances equity; `FX gain/loss` leaf), per-currency leaves following the category rule.
- **Records + repositories** (JdbcClient): `Account`, `Transaction`, `Posting`, `ExchangeRate`,
  mapped from rows.
- **Domain-ops service** (§1.4): `recordTransaction` (validates balanced postings — native sum for
  single-currency, base sum with frozen `base_amount` for cross-currency — before insert),
  `voidTransaction` (soft-delete cascading to postings), `editTransaction`.
- **Cross-currency is live here:** a non-par conversion freezes `base_amount` on its legs and routes
  the residual to the `FX gain/loss` leaf (data-model §6.3/§6.4). Exercised by real ops + tests.
- **Invariants as SQL-logic tests** (data-model §8): conditional sum-to-zero (both branches),
  leaves-only, currency consistency, soft-delete coherence.
- **Running-balance query** (windowed `sum` per account) — SQL-logic tier; the backdated-insert
  correctness test (invariant 5) starts here even though the register that surfaces it is stage 7.

**Done when:** the engine records, voids, and edits balanced transactions including cross-currency
conversions; all invariants are tested against real Postgres; running balance computes correctly
including backdated inserts.

### Stage 4 — UI container (the shell)
**Goal:** the server-rendered scaffold every later screen hangs on.
- Base Thymeleaf layout; htmx wired; the dense, restrained, spreadsheet-like house style
  (FR-UX-04; consult the frontend-design conventions); navigation shell.
- The **keyboard-layer leaf** scaffolded (small isolated Alpine.js/vanilla module — tech-stack §4.3),
  reviewed as the UI's main maintainability risk.
- **German formatting** utilities (display + parse): `1.234,56`, base currency bare, non-base carries
  symbol/ISO (register §2.9, NFR-08) — cross-cutting, lives here.
- A trivial demo page proving fragment swaps and keyboard nav work.

**Done when:** the shell renders, htmx swaps work, keyboard nav moves focus, numbers format
German-style.

### Stage 5 — Settings UI
**Goal:** the smallest real screen, the greeting, and the first-run base-currency gate.
- Settings screen over the §1.3 entity: **base currency** — set on first run (chosen from seeded
  currencies), then shown **read-only** once locked; editable **display name**.
- "Hello, %name%" landing reads the display name.
- Confirm the write-once guard end to end (UI cannot change a locked base currency).

**Done when:** base currency can be set once on a fresh book and is read-only thereafter; a name set
in settings shows in the greeting.

### Stage 6 — Accounts & categories (with opening balances)
**Goal:** the reference data the register needs, plus the equity plumbing.
> Prerequisite ordering holds: base currency is set (stage 5) before any account, since every account
> carries a currency.
- **Account management UI:** create/edit/close accounts (name, type, parent, **currency — any seeded
  currency**, multi-currency live per §1.2). Stored two-tone hue per account assigned here
  (register §2.8).
- **Category management UI:** the *same* table, presented as a separate income/expense list
  (data-model §3.2). Create-new `Parent - Child` with inherited type; the per-currency leaf is
  resolved from the paying account at post time (register §3.5, data-model §6.5). Optionally seed a
  starter category tree.
- **Opening balances:** account creation can take an opening balance, posting as a transaction
  (`Asset +X` / `Opening Balances −X`, sum-to-zero ✓ — data-model T-DM-4) through the stage-3 engine,
  against the per-currency Opening Balances leaf.
- **`operations` module is born here, minimal:** the **subdivision** op (promote a former leaf,
  reassign its postings to a `…:General` leaf) — required by category-create (register §3.5 /
  data-model §5). The broader merge/reassign suite is backlog (§14).

**May split** into 6a (accounts + opening balances) and 6b (categories + subdivision) if the slice
feels large.

**Done when:** accounts (any currency) and categories can be created and managed; an account can be
opened with a starting balance that is a real, balanced transaction; subdividing a leaf works.

---

> **From here, plan is rough by intent.** Stage 6 puts a usable UI in front of the owner; expect the
> ordering and detail below to be revised once that feedback arrives.

### Stage 7 — Transaction register (rough)
The two central surfaces from the register doc: the newest-at-bottom list (rows = postings to viewed
accounts; per-account running balance; two-tone-per-account zebra; `hx-swap="beforeend"` insert — the
register doc supersedes tech-stack §4.2 on direction; non-base accounts shown per §2.9) and the
persistent bottom **entry/edit dock** (payee/category/tag pickers, sign-free amount entry, the
single-ghost-category autofill rule, the inline split panel, cross-currency entry). Backdated-insert
slice refresh (OOB swap vs bounded re-fetch — Q-UI-5). First **Playwright** smoke test (transaction
entry → commit). *Exact keyboard state machine deferred to implementation (Q-UI-2).*

### Stage 8 — People & per-person debts (rough)
The `debts` module: `person` + `account_owner`; auto-provisioned signed per-person/per-currency
`asset` accounts (data-model §7). Register extension for the `→ Max` / `Max →` arrow-chip display and
entry (register §2.6, §3.3, §3.8), including the Account-vs-Category column rule. Resolves or carries
Q-UI-1 (whether person-funded pure-expense rows surface in the default register).

### Stage 9 — Receipts backend (rough)
Ratify the provisional `receipt` / `receipt_line` schema (receipt doc §9) **into the data-model
doc**, then implement: migrations, records, repositories, the state machine (receipt doc §2.1), and
the **Pi filesystem storage** abstraction (ARCH-07: immutable `original_path`, derived `edited_path`).
No AI yet.

### Stage 10 — Receipt upload UI (rough)
Mobile camera-only capture → raw upload → `new` (receipt doc §4). PC receipt **register**
(master-detail, state as primary filter, §5) and the **pre-process** step (client-side Cropper.js
leaf + pixel pass; manual cropping only — supersedes tech-stack §5.2; downscale-before-send).

### Stage 11 — AI processing backend (rough)
The pluggable parser provider (ARCH-03, ≥ Sonnet 4.6; Anthropic Java SDK / Spring AI) behind a clean
interface; sends only the image + instructions, never the ledger (ARCH-08). Background worker;
`processing` state; htmx polling for completion (T-RX-1). Both modes: **Messages API** (single,
sync-feeling) and **Batches API** (bulk, async, −50% — receipt doc §3). Token tricks (tight output
schema, downscaling, prompt caching with the per-receipt AI note as a non-cached suffix, optional
Haiku→Sonnet escalation).

### Stage 12 — Receipt lifecycle UI + transaction integration (rough)
Post-process (image left, full split toolkit right — reuses the register's pickers), the
`remaining 0,00 ✓` parse-sanity readout, the redistribute-tax helper, the per-receipt AI note, and
**confirm-time** transaction creation with duplicate detection and the 1:0..1 receipt↔transaction
link (receipt doc §6–§7). Adds Playwright smoke for receipt review → commit.

### Stage 13 — Bank statement reconciliation (rough)
The `statements` module (§5.8): PDF-first extraction, matching statement lines against existing
transactions (manual/receipt/pre-registered), flag-and-create for unmatched, manual override, mark
reconciled. Shares matching logic with receipt duplicate detection. Adds Playwright smoke for
statement match → confirm.

---

## 14. Backlog inventory (unordered)

Requirements not yet placed in a stage. **No ordering, no stages assigned** — priority is set during
implementation, once the system is in use. Listed by area so nothing is forgotten.

- **Reporting & analysis:** category×month matrix (FR-ANA-07); consolidated-balance timeline + trend
  line (FR-ANA-09); drill-down from cells (FR-ANA-10); spend by category/tag/payee, period
  comparisons, category trends (FR-ANA-01–04); net worth in base incl. **held-balance revaluation**
  (FR-ANA-05, §1.2); monthly narrative report (FR-RPT, Q12).
- **Currency follow-ons:** ECB rate-feed automation (§1.2 — the engine is already multi-currency;
  this only automates rate lookup/proposal).
- **Recurring & subscriptions:** recurring templates generating `pending_review` transactions
  (FR-REC); subscriptions manager + renewal overview (FR-SUB).
- **Planning:** budgets on the category taxonomy (FR-BUD); forecasting — scheduled + trend (FR-FC).
- **Data lifecycle:** Money-history importer + canonical import representation (FR-IMP-01–04, gated on
  Q9 — export format); generic CSV importer (FR-IMP-05); **full** data-management operations suite —
  merge categories/payees/people/accounts, bulk re-tag/re-categorize (FR-DM; grows the `operations`
  module from its stage-6 subdivision seed).
- **Investments:** manual holdings/positions contributing to net worth (FR-INV, "Could").
- **Exposure & integrations:** MCP server (FR-MCP; Q11 scope must be settled first); Telegram
  quick-capture bot (FR-TG, "Could").
- **Ops & hardening:** minimal auth (ARCH-04 — prerequisite for any non-localhost, Telegram, or MCP
  exposure); Docker/compose for the Pi (ARCH-01); documented backups + export (NFR-03); HTTPS reverse
  proxy (ARCH-05).

---

## 15. Testing strategy (the three tiers)

| Tier | Source set | Runs against | Tests | Speed |
|------|-----------|--------------|-------|-------|
| **Unit** | `test` | nothing (Mockito mocks the repo/integration points) | Service orchestration & validation logic (e.g. `recordTransaction` rejects unbalanced input *before* the DB) | Fast |
| **Integration** | `integrationTest` | Testcontainers Postgres | Flyway migrations apply cleanly on a fresh container; repositories map rows ↔ records correctly | Slower |
| **SQL-logic** | logic suite | Testcontainers Postgres | Logic that *lives in* SQL and cannot be mocked: the matrix query, running balances, tag rollups, conditional sum-to-zero, FX valuation | Slower |

- **The unit tier is thin by design** for query-heavy modules. When the real logic is in the SQL, the
  SQL-logic tier carries the weight — do not pad the unit tier to chase a coverage number; that buys
  false confidence (H2 wouldn't even run the constructs — tech-stack §2.5).
- **Integration vs SQL-logic share the same technical setup** (real Postgres); the split is
  *organizational* (plumbing vs logic), kept as separate suites because that worked for the owner. A
  single Postgres-backed source set partitioned by tag is the lighter alternative if the split ever
  grates.
- **Container reuse** (singleton container / `testcontainers.reuse.enable=true`) is important from
  stage 2 so the Postgres-backed suites don't pay startup per class and the TDD loop stays tight.
- **Migrations** are forward-only (Flyway Community); "tested migrations" (NFR-06) = apply on a fresh
  container and assert the resulting schema/data, plus data-preserving checks where a migration
  transforms existing rows.
- **UI tier (added when flows exist):** thin **Playwright** smoke tests only on money-critical flows
  (transaction entry, receipt review→commit, statement match→confirm — tech-stack §4.4). Do **not**
  unit-test templates; server-rendering means most "UI logic" is backend logic already covered above.

---

## 16. Open decisions folded into this plan (confirm or overturn)

| # | «A» working assumption baked into this plan | Overturn impact |
|---|----------------------------------------------|-----------------|
| P-1 | Multi-currency fully live from stage 3 (cross-currency, frozen `base_amount`, `FX gain/loss` residual, `exchange_rate` table); only ECB feed automation + held-balance revaluation arrive with the reports/integration that consume them | Re-scopes stage 3 vs §14 |
| P-2 | Root `settings` entity in the DB holds base currency (write-once, required before any transaction); entity born at stage 3, UI at stage 5; now ratified into the data-model doc (§3.8) | Small schema; entity defined |
| P-3 | Stage 6 covers accounts **and** categories **and** opening balances; `operations` born here (subdivision) | Re-scopes stage 6 |
| P-4 | Stage-3 service is invariant-upholding domain ops, not generic CRUD | Naming/shape of the `ledger` service |
| P-5 | Money type picked at stage 2 (T1); test suites named at stage 2 | Low-risk, local |

---

### Decisions captured this round (summary)
- **Module-first packaging + `verify()` test from the skeleton** (stage 2), not retrofitted.
- **Multi-currency fully live from stage 3** — no single-currency phase; only the ECB feed automation
  and held-balance revaluation defer, as feed/reporting concerns.
- **Base currency lives in a root `settings` entity in the DB**, write-once, required before any
  transaction; the entity is born at stage 3 (the engine needs it), UI at stage 5; now ratified into
  the data-model doc (§3.8).
- **Categories, opening balances, and the subdivision op land together at stage 6**, since categories
  are accounts and opening balances are transactions.
- **The core service is a domain-operations layer** (`recordTransaction`/`voidTransaction`), not CRUD.
- **Three test suites, real Postgres, reused containers**; unit tier thin by design; Playwright only
  on money-critical UI flows.
- **Stages 7+ kept rough; §14 is an unordered inventory** — backlog priority set during implementation.