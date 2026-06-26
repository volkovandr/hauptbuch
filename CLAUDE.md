# CLAUDE.md

Guidance for Claude Code working in this repository. This file is **operational** — how to build
correctly here. *What* to build next lives in `docs/implementation-plan.md`.
The five design docs in `docs/` are **authoritative** for every product and domain decision; this
file only summarises the parts you will trip over. Keep it in sync with `docs/` when conventions change.

> A self-hosted, single-user, web-based Microsoft Money replacement: a double-entry ledger with
> multi-currency, AI receipt ingestion, and an MCP surface, built as a Spring Boot **modular
> monolith** and run on a Raspberry Pi. Java + Thymeleaf + htmx + JdbcClient + Flyway + PostgreSQL.

---

## 0. The prime directive — read before writing any code

**The codebase must stay navigable and correct by a human alone, without AI assistance.** This
outranks cleverness, brevity, and raw capability every time. Concretely:

- **Write boring, uniform, idiomatic Java/Spring.** Do not invent a second way to do something that
  already has a way. If you catch yourself adding a new pattern next to an existing one, **stop** and
  follow the existing one.
- **Follow the established patterns** in the touched module. Match its structure, naming, and test
  shape. Consistency is the feature.
- **When an accounting, currency, or product rule is unclear, read the relevant `docs/` file — do not
  infer financial behaviour.** Guessing at double-entry or FX semantics produces subtly wrong money.
- **Prefer small, reviewable changes.** The owner reviews by hand; a large opaque diff defeats the
  point of the whole project.

---

## 1. Hard rules (these have teeth — many fail the build)

1. **Module-first packaging, enforced.** A top-level package under the app root *is* a Spring
   Modulith module (organise by feature/vertical slice). **Never** create layer-first packages
   (`controllers/`, `services/`, `repositories/` at the root). A module may only call another
   module's **public top-level types**; its sub-packages are internal.
2. **Run the module-verification test after any structural change.** `ApplicationModules.verify()`
   lives in the `test` suite. A red module test means you broke an architecture boundary or created a
   cycle — **fix the boundary, never suppress the test.**
3. **Native SQL only — `JdbcClient` + Java `record`s.** **No ORM (JPA/Hibernate). No jOOQ.** SQL is
   written literally and lives visibly in the repository.
4. **Money is never `double`/`float`.** Use the project money type (Joda-Money / JSR-354; see
   tech-stack T1). DB monetary columns are `numeric`.
5. **Tests run against real PostgreSQL via Testcontainers. Never H2** — H2 lacks the window functions
   and `generate_series` the analytics rely on.
6. **UI is server-rendered Thymeleaf + htmx. No SPA, no npm, no bundler, no TypeScript.** Bespoke JS
   is confined to two isolated *leaves* only: the keyboard layer and the Cropper.js image component.
   Do not thread JS through the app.
7. **The service layer upholds invariants — it is domain operations, not CRUD.** Recording a
   transaction creates balanced postings and enforces sum-to-zero and leaves-only. Generic per-table
   CRUD is only for true reference data (payees, tags, account definitions).
8. **AI calls send only the document + parsing instructions — never the ledger, balances, or DB**
   (ARCH-08). Secrets (AI keys, DB creds) come from config/env, never hardcoded, never committed.

---

## 2. Commands

```bash
# build everything + all test suites + module verification
./gradlew check

# run the app (JVM; needs the dev Postgres up)
./gradlew bootRun

# dev database (PostgreSQL runs natively/locally, not in the app container)
docker-compose up -d        # local dev Postgres

# the three test suites (Gradle JVM Test Suite plugin)
./gradlew test              # unit (Mockito, no container) — includes ApplicationModules.verify()
./gradlew integrationTest   # Flyway + repositories vs Testcontainers Postgres
./gradlew sqlLogicTest      # SQL-resident logic vs Testcontainers Postgres
```

Flyway migrations apply automatically on app start and in the Postgres-backed suites. Keep
Testcontainers **reuse** on (`testcontainers.reuse.enable=true`) so the loop stays fast.

---

## 3. Architecture — the modular monolith

One deployable Spring Boot app; internal boundaries are **real and verified** (§1.2). Modules
(top-level packages under `com.<app>.finance`):

| Module | Responsibility |
|--------|----------------|
| `ledger` | core double-entry: accounts, transactions, postings, transfers, balances |
| `accounts` | account definitions & types |
| `categories` | hierarchical categories + tags (shared taxonomy) |
| `debts` | per-person auto-managed shared-expense ledger |
| `receipts` | receipt ingestion + review |
| `statements` | bank-statement reconciliation |
| `budgets` | budgets on the expense taxonomy |
| `recurring` | recurring templates + subscriptions |
| `analytics` | matrix report, balance timeline, narrative report |
| `forecasting` | scheduled + trend projection |
| `importer` | format-agnostic import via a canonical representation |
| `operations` | first-class data-management ops: merge / reassign / subdivide |
| `mcp` | MCP server exposing the operations + read tools |

**`operations` is the single home for structural domain operations**, called by **both** the UI and
the MCP server — the same validated, audited, reversible operation regardless of caller.

**Do not** enable Spring Modulith's event-publication / outbox machinery — it is deliberately
deferred in v1. Use direct service calls.

---

## 4. Data model — load-bearing facts you will get wrong otherwise

Full detail in `docs/data-model.md`. The traps:

- **Sign convention: `+` = debit, `−` = credit. Every transaction's postings sum to zero**, for any
  mix of account types, by construction. This is the cheapest correctness check — do not break it.
- **Categories are backed by accounts** (`income`/`expense` type). There is **no separate category
  table**; a "category" is a set of same-named `income`/`expense` accounts, one per currency (§6.5).
  Spending €5 on coffee is `Cash −5, Food +5`. The `categories` module owns the logic that keeps
  these backing accounts consistent (e.g. subdividing a leaf that already has postings) — logic that
  applies to categories but not to other accounts like cash.
- **Per-person debts ARE accounts** — one signed `asset` account per (person, currency), allowed to
  go negative; the **sign of the balance is the direction**. No receivable/payable accounts,
  no `beneficiary_id`.
- **`transaction` carries NO amount.** The amount lives in the postings; a transaction total is a
  `sum` of postings.
- **Leaves-only posting for accounts** (a parent balance = sum of descendants). **Tags are NOT
  leaves-only** — a posting may carry a parent tag and leaf tags together. Do not assume the two
  hierarchies behave the same.
- **Categories are per-currency at the leaf** (`Food-EUR`, `Food-CHF` under `Food`). The leaf
  currency is *determined by the paying account*, never chosen. The user picks `Food` semantically;
  routing to the currency leaf is automatic.
- **`base_amount` is nullable on purpose.** `NULL` = derive on the fly from rates; **non-null = a
  frozen base-currency fact from a real cross-currency conversion event, never recomputed.**
- **Two valuation rules — do NOT unify them.** Flows (income/expense in a period) are valued
  posting-by-posting at each posting's date's rate. Balances/net worth are valued as
  `native_balance × rate@report-date` (mark-to-market). The difference *is* unrealized FX.
- **FX policy #3:** holding-period FX appreciation lives **only** in net worth — it books no posting.
  The `FX gain/loss` leaf is booked **only** at genuine two-currency conversion events (the residual
  that balances a non-par conversion). A rate number changing is **not** an event.
- **Conditional sum-to-zero:** single-currency transaction ⇒ `sum(amount)=0`; cross-currency ⇒ every
  leg has `base_amount` and `sum(base_amount)=0`.
- **Base currency is a write-once row in the `settings` entity** (single-row table), required before
  any transaction and immutable thereafter. The engine refuses to record a transaction while it is
  unset. (See the plan §1.3 — being ratified into the data-model doc.)
- **No materialized balances, no universal materialized base amounts.** Compute on the fly
  (windowed sums, carry-forward rate joins). Add a cache only when *measured* slow.
- **`lifecycle` and `deleted_at` are orthogonal axes** on `transaction` (and receipt state) — two
  columns, never one merged enum. Soft-delete is reversible; integrity checks scope to
  `deleted_at is null`.

---

## 5. Conventions

- **Naming (governs every table):** every entity has a surrogate PK `<entity>_id`; an FK **reuses the
  target's PK name** (`posting.account_id` → `account.account_id`). Exceptions: externally-defined
  entities keep their natural key (`currency.currency_code`); self-references use a role name
  (`parent_id`). Junction tables get their own `<junction>_id` PK with the natural pair as `unique`.
- **SQL style:** explicit `ON` joins (never `USING`). Literal, readable Postgres. Window functions
  and `generate_series` are expected and fine — they are why we test on real Postgres.
- **Migrations:** Flyway, plain versioned `.sql`, forward-only. A migration is "tested" when it
  applies cleanly on a fresh container and the resulting schema/data is asserted.
- **Row mapping:** Java `record`s, mapped via `JdbcClient`. No entity graphs.
- **Formatting:** German display/parse (`1.234,56`); base currency rendered bare, non-base carries
  symbol/ISO. Lives in the shared UI formatting utility.
- **Tests are the living spec.** When you return to a module, the tests state what it does — write
  them that way.

---

## 6. Testing — three tiers, and where logic goes

| Suite | Against | Holds |
|-------|---------|-------|
| `test` (unit) | nothing — Mockito mocks the DB | service orchestration & validation logic (e.g. reject unbalanced input *before* the DB). **Thin by design** for query-heavy modules — do not pad it. |
| `integrationTest` | Testcontainers Postgres | Flyway migrations apply; repositories map rows ↔ records |
| `sqlLogicTest` | Testcontainers Postgres | logic that *lives in SQL* and cannot be mocked: matrix query, running balances, tag rollups, conditional sum-to-zero, FX valuation |

- **TDD.** For SQL-resident logic, write the `sqlLogicTest` first with crafted data — including the
  cross-currency and backdated-insert cases — then implement the query.
- **Do not unit-test Thymeleaf templates.** Server rendering means most "UI logic" is backend logic
  already covered above. Money-critical *flows* get thin **Playwright** smoke tests only (transaction
  entry, receipt review→commit, statement match→confirm).

---

## 7. Workflow for a change

1. **Read** the relevant `docs/` file for any domain rule you are about to touch.
2. **Write the test first** (the right tier per §6).
3. **Implement** following the existing patterns in that module.
4. **Run** `./gradlew test` (includes module verification) plus the relevant Postgres-backed suite.
   If the module test is red, you broke a boundary — fix it.
5. **Keep bespoke JS in its leaf.** If a change tempts you toward an SPA, a build step, or
   app-wide JS, stop — that violates §1.6.

---

## 8. Do NOT

- Add an ORM, jOOQ, or any hidden-SQL data layer.
- Use H2 (or any non-Postgres) in tests.
- Represent money as `double`/`float`.
- Package layer-first, or reach into another module's internals.
- Introduce a React/Svelte/Vue SPA, npm, a bundler, or TypeScript.
- Build a generic CRUD layer where a domain operation is required.
- Send the ledger, balances, or DB contents to any AI provider — only the document being parsed.
- Enable the Modulith event-publication registry (deferred in v1).
- Expose raw SQL through the MCP server — structured domain tools only.
- Auto-apply image transforms or AI-suggested crops — image pre-processing is manual, client-side.
- Recompute a non-null `base_amount`, or materialize running balances, without an explicit decision.
- Hardcode or commit secrets.

---

## 9. The design docs (`docs/`) — authoritative; read before implementing

- **`requirements.md`** — scope, UX philosophy, the full feature list and priorities.
- **`tech-stack.md`** — every technology decision *with its rationale and rejected alternatives*.
- **`data-model.md`** — the double-entry engine, currency/FX, per-person debts, tags; the invariants.
- **`ui-transaction-register.md`** — the register and the entry/edit dock; display & entry rules.
- **`ui-receipt-processing.md`** — receipt lifecycle, the four-step workflow, the `receipt` schema sketch.
- **`implementation-plan.md`** — the staged build sequence and the backlog. *Start here for what's next.*