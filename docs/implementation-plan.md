# Hauptbuch — Implementation Plan

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.16
**Date:** 2026-07-05
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
- **v0.17 (2026-07-08):** Stage 7b (Entry dock, simple transactions) marked **complete**.
- **v0.16 (2026-07-05):** Stage 7a (Register, read-only) marked **complete**.
- **v0.15 (2026-07-05):** Stage 7 made concrete and split into **7a–7e** in the new dedicated
  sub-plan `implementation-plan-stage-7.md`. Scope decisions: tags last (7e, schema migrates then);
  cross-currency entry after edit/splits (7d); keyboard-first as each piece lands (Q-UI-2 piecewise);
  column re-sorting + the balance-hide rule deferred to §14. Payee gains city/country + a seeded
  `country` table at 7b; the dock's commit endpoint lives in `operations` (module cycles — the 6d
  precedent).
- **v0.14 (2026-07-04):** Stage 6d (Currency-list editor) marked **complete** — with it, all of
  Stage 6 (6a–6d) is done.
- **v0.13 (2026-07-04):** Stage 6d scope narrowed: `createCurrency` provisions the two **system**
  backing leaves only (`Opening Balances <CODE>`, `FX gain/loss <CODE>`), **not** a back-filled leaf
  under every category parent. The earlier "back-fill category leaves" line contradicted data-model
  §6.5, where a category's currency-leaf appears lazily on first spend via subdivision; eager
  back-fill would manufacture the empty per-currency leaves §6.5 avoids.
- **v0.12 (2026-07-04):** Stage 6c (Category deletion) marked **complete**. The target-leaf rule was
  refined during implementation: "leaf" is judged **after** the deletion, so an ancestor whose only
  surviving children are inside the deleted subtree is a valid target (deleting `M&Ms` lets `Sweets`
  receive the postings) — the initial cut wrongly excluded every current parent.
- **v0.11 (2026-07-04):** Inserted a new **6c — Category deletion** (leaf or whole-subtree delete,
  postings reassigned to a user-picked surviving leaf outside the deleted subtree); the former 6c
  (currency-list editor) renumbered to **6d**, unchanged in scope. Prompted by testing 6b: category
  create/rename exist but there was no way to remove a mis-created category.
- **v0.10 (2026-07-04):** Stage 6b implemented: subdivision is **implicit**, not a standalone UI
  action — creating a category whose chosen parent is currently a posted-to leaf triggers it
  automatically, adding the requested child plus an `Uncategorized` catch-all sibling that absorbs
  the leaf's existing postings. Category creation takes no currency (unlike accounts): every
  category leaf is created in the book's base currency; per-currency leaves arrive with real usage
  once the register exists. `subdivideAccount` itself is generic over any account type, living in
  the new `operations` module; `categories` is its first caller.
- **v0.9 (2026-07-03):** Stage 6a (Accounts + opening balances) marked **complete**.
- **v0.8 (2026-07-01):** Stage 6 formally split into **6a** (accounts + opening balances), **6b**
  (categories + subdivision), and a new **6c** (currency-list editor). 6c adds user-managed
  currencies via an "Add currency…" affordance in every picker (base picker included); it is a
  `createCurrency` **operation** that provisions per-currency backing leaves (back-filling under
  existing parents), reusing 6b's provisioning path. Interaction is htmx + native `<dialog>` with
  **no bespoke JS** (§1.6 preserved).
- **v0.7 (2026-07-01):** Stage 5 (Settings UI) marked **complete**.
- **v0.6 (2026-06-30):** Stage 4 (UI shell) marked **complete**; a `web` module (UI shell only)
  was added to the §3 module map — feature controllers stay in their own modules.
- **v0.5 (2026-06-28):** Stage 3 (Transaction core) marked **complete** 
- **v0.4 (2026-06-28):** Stage 2 (Skeleton) marked **complete** 
- **v0.3 (2026-06-26):** Stage 1 (Project setup) marked **complete**
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

### Stage 1 — Project setup ✅ **complete**
**Goal:** a repository a stranger (or the owner, months later) can orient in.
- ✅ GitHub repo; license; `README.md`; `CLAUDE.md` (state the Modulith rule with teeth — "boundaries
  are enforced by `ApplicationModules.verify()`; run the module test after changes" — per tech-stack
  §3, not a prose description the agent may ignore).
- ✅ Move the design docs into the repo (`/docs`), so the *why* travels with the code.
- ✅ `.gitignore`.

**Done when:** repo cloneable, docs in place, contribution conventions written down. — **Met.**

### Stage 2 — Skeleton ✅ **complete**
**Goal:** an empty but *enforced* modular monolith that boots and tests green.
- ✅ Spring Initializr (Java 25, Spring Boot 4.1, Web, Thymeleaf, JdbcClient, Flyway, Testcontainers).
  *Validation deferred — added when a screen first needs it.*
- ✅ Module-first packages from §1.1 (empty stubs), each an explicit `@ApplicationModule`; plus an
  **open** `shared` kernel module for cross-cutting types (the money type lives there).
- ✅ Spring Modulith dependency + the `verify()` test (§1.1) in the unit suite. **Scoped to
  `spring-modulith-starter-core`** — the `-jdbc` starter (event-publication/outbox registry) is
  deliberately **not** used (events deferred in v1 — CLAUDE.md §3/§8).
- ✅ Gradle test suites scaffolded (§1.5) via the JVM Test Suite plugin: `test`, `integrationTest`,
  `sqlLogicTest`, each with a sample green test; Testcontainers Postgres singleton + reuse wired;
  `check` runs all three plus module-verify.
- ✅ Money type chosen — **Joda-Money** (T1 / P-5) — with a thin `MoneyFactory` wrapper (one
  sanctioned, currency-rounded construction path; does not hide Joda-Money).
- ✅ Local `docker-compose` for a dev Postgres on host port **15432** (app stays on the JVM for now).

**Done when:** `./gradlew check` runs all three suites and the module-verify test, all green; app
boots against the compose Postgres. — **Met** (all green; boots against compose Postgres 17.9 on
15432, Flyway applies its empty migration set cleanly).

### Stage 3 — The transaction core (the engine, fully multi-currency) ✅ **complete**
**Goal:** the uniform posting model, correct and tested, with **no UI**.
- ✅ **Settings entity** (§1.3) + migration: single-row `settings`, write-once `base_currency`. The
  engine treats "base currency set" as a precondition for recording transactions. (Tests set it via
  fixtures; the UI to set it is stage 5.)
- ✅ **Migrations (Flyway):** `currency` (seed the currencies actually used — data-model §3.1),
  `account`, `transaction`, `posting`, `exchange_rate`, `payee`. Seed the **system accounts**
  (Opening Balances equity; `FX gain/loss` leaf), per-currency leaves following the category rule.
- ✅ **Records + repositories** (JdbcClient): `Account`, `Transaction`, `Posting`, `ExchangeRate`,
  mapped from rows.
- ✅ **Domain-ops service** (§1.4): `recordTransaction` (validates balanced postings — native sum for
  single-currency, base sum with frozen `base_amount` for cross-currency — before insert),
  `voidTransaction` (soft-delete cascading to postings), `editTransaction`.
- ✅ **Cross-currency is live here:** a non-par conversion freezes `base_amount` on its legs and routes
  the residual to the `FX gain/loss` leaf (data-model §6.3/§6.4). Exercised by real ops + tests.
- ✅ **Invariants as SQL-logic tests** (data-model §8): conditional sum-to-zero (both branches),
  leaves-only, currency consistency, soft-delete coherence.
- ✅ **Running-balance query** (windowed `sum` per account) — SQL-logic tier; the backdated-insert
  correctness test (invariant 5) starts here even though the register that surfaces it is stage 7.

**Done when:** the engine records, voids, and edits balanced transactions including cross-currency
conversions; all invariants are tested against real Postgres; running balance computes correctly
including backdated inserts. — **Met.**

### Stage 4 — UI container (the shell) ✅ **complete**
**Goal:** the server-rendered scaffold every later screen hangs on.
- Base Thymeleaf layout; htmx wired; the dense, restrained, spreadsheet-like house style
  (FR-UX-04; consult the frontend-design conventions); navigation shell.
- The **keyboard-layer leaf** scaffolded (small isolated Alpine.js/vanilla module — tech-stack §4.3),
  reviewed as the UI's main maintainability risk.
- **German formatting** utilities (display + parse): `1.234,56`, base currency bare, non-base carries
  symbol/ISO (register §2.9, NFR-08) — cross-cutting, lives here.
- A trivial demo page proving fragment swaps and keyboard nav work.

**Done when:** the shell renders, htmx swaps work, keyboard nav moves focus, numbers format
German-style. — **Met.**

### Stage 5 — Settings UI ✅ **complete**
**Goal:** the smallest real screen, the greeting, and the first-run base-currency gate.
- Settings screen over the §1.3 entity: **base currency** — set on first run (chosen from seeded
  currencies), then shown **read-only** once locked; editable **display name**.
- "Hello, %name%" landing reads the display name.
- Confirm the write-once guard end to end (UI cannot change a locked base currency).

**Done when:** base currency can be set once on a fresh book and is read-only thereafter; a name set
in settings shows in the greeting.

### Stage 6 — Accounts & categories (with opening balances) ✅ **complete**
**Goal:** the reference data the register needs, plus the equity plumbing.
> Prerequisite ordering holds: base currency is set (stage 5) before any account, since every account
> carries a currency.
> **Module-boundary note (carried from stage 3):** the `Account` record and a *read-only*
> `AccountRepository` were born in **`ledger`** at stage 3, because the engine needs to read accounts
> (currency lookup, leaves-only check, resolving a system leaf) before the `accounts` module exists.
> **Stage 6 moves ownership of `Account` (and the account table's writes) into `accounts`**, exposes a
> read API from it, and has `ledger` depend on `accounts`' public type — closing the deliberate
> stage-3 placement so a concept does not stay split across two modules. (Decision: keep in `ledger`
> for stage 3, relocate here. `verify()` stays green throughout — repositories were package-private,
> so nothing cross-module was ever exposed.)
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

**Split (confirmed):** the slice is large, so stage 6 is built as three ordered sub-stages, each
ending green and demoable:

- **6a — Accounts + opening balances.** ✅ **complete.** Move `Account` ownership into `accounts`;
  account management UI (any seeded currency); opening balance as a real balanced transaction.
- **6b — Categories + subdivision.** ✅ **complete.** Category management over the same table; the
  `operations` module is born here with the **subdivision** op (leaf → parent, postings reassigned
  to an `Uncategorized` sibling), triggered implicitly by creating a child under a posted-to leaf.
- **6c — Category deletion.** ✅ **complete.** Unlike accounts (closed/reopened, never deleted), a
  category is truly deleted: the user picks a **surviving leaf** (never inside the subtree being
  deleted) that
  receives every reassigned posting, then confirms. Deleting a parent deletes its **whole subtree**
  (every descendant, not just the parent row) after moving all of their postings — descendants,
  not only the parent's own — onto the chosen target. This is a `deleteCategory` **operation** in
  `operations`, reusing `subdivideAccount`'s posting-reassignment repository in reverse (many
  sources → one target instead of one source → new target).
  - **Validation:** the target must be a live leaf **after** the deletion — not the category being
    deleted nor any of its descendants (rejects a self-referential target, data-model §5), and with
    no children left *outside* the subtree. A node whose only children are inside the deleted subtree
    becomes a leaf once they are gone, so it is a valid target (e.g. deleting `M&Ms` lets its parent
    `Sweets` receive the postings).
  - **UI:** the category edit page gains a "Delete" panel — pick the target from a leaf-only picker,
    one confirm button (no second are-you-sure step, consistent with account close/reopen), with a
    plain-text warning in the destructive/oxblood ink noting the operation is **irreversible**.
  - **TDD:** `sqlLogicTest`/integration coverage for a childless leaf (simple reassign-then-delete),
    a parent with children (cascade), and the rejected-target-inside-subtree case.
- **6d — Currency-list editor.** ✅ **complete.** An "Add currency…" affordance in **every** currency
  picker (including the first-run base-currency picker). Adding a currency is not one insert — it provisions
  the currency's per-currency **system** backing leaves (`Opening Balances <CODE>`, `FX gain/loss
  <CODE>`), mirroring what the V2 seed does per currency on an empty book (data-model §3.1/§6.3), so
  opening-balance entry and cross-currency conversion work in the new currency the moment it exists
  (CLAUDE.md §1.7, §4). It does **not** pre-create a leaf under every category parent: per data-model
  §6.5 a category's currency-leaf appears **lazily** — the first time you actually spend that
  currency, via the stage-6b subdivision path — so eagerly back-filling category leaves would
  manufacture the empty per-currency leaves §6.5 deliberately avoids. So it is a `createCurrency`
  **operation** in `operations`, not CRUD, reusing `accounts`' leaf-insertion path.
  - **Interaction (no bespoke JS — htmx + native `<dialog>`, upholds CLAUDE.md §1.6):** a reusable
    Thymeleaf currency-picker fragment (`<select>` + "Add currency…" button) lives with the currency
    in `ledger`'s template dir; screens include it. The button `hx-get`s a `<dialog>` add-form;
    **OK** `hx-post`s to create + provision, and the response `hx-swap-oob`s the picker back with the
    new currency **pre-selected** (its empty dialog-mount target dismissing the dialog); **Cancel**
    clears the mount. The htmx-driving *controller* lives in `operations`, not `ledger` — `ledger →
    operations` would close a module cycle with `operations → ledger` (the currency insert). Two
    implementation gotchas, both worth remembering: htmx attributes are emitted via `th:attr` (there
    is no htmx Thymeleaf dialect, so `th:hx-*` silently drops), and the modal backdrop is a sibling
    `<div>`, not a `::before` (a pseudo tints the slip). This is the app's first htmx partial-swap
    and first `<dialog>`; minimal `<dialog>` CSS centres it with a pure-CSS backdrop (no
    `showModal()`).
  - **TDD:** the `createCurrency` test asserts the two system leaves land under the right parents
    (`Opening Balances`, `FX gain/loss`) and are the new currency's, on both a fresh book and a book
    that already has categories (confirming categories are *not* back-filled — they stay lazy, §6.5).

**Done when (6a–6d):** accounts (any currency) and categories can be created and managed; an account
can be opened with a starting balance that is a real, balanced transaction; subdividing a leaf works;
a category (leaf or whole subtree) can be deleted with its postings reassigned to a chosen surviving
leaf; a new currency can be added from any picker and is provisioned with its backing leaves and
pre-selected.

---

> **From here, plan is rough by intent.** Stage 6 puts a usable UI in front of the owner; expect the
> ordering and detail below to be revised once that feedback arrives.

### Stage 7 — Transaction register & entry dock
The two central surfaces from `ui-transaction-register.md`: the newest-at-bottom register and the
persistent bottom entry/edit dock. **Detailed in the dedicated sub-plan
`implementation-plan-stage-7.md`** (the >30-line rule); UI inspiration mock-ups in
`docs/pic/register-*.png`. Five ordered sub-stages, each green and demoable; keyboard-first as each
piece lands (Q-UI-2 decided piecewise, never retrofitted):

- **7a — Register, read-only.** ✅ **complete.** The list over a register query + the stage-3
  running-balance SQL rebound to a real repository; date/account/payee filters (column re-sorting
  deferred to §14); zebra, currency display, muted `pending_review`.
- **7b — Entry dock, simple transactions.** ✅ **complete.** Payee picker with create-new parsing
  (payee gains city/country + a seeded ISO-3166 alpha-3 `country` list), category picker with
  create-new and the lazy per-currency-leaf routing (data-model §6.5), sign-free amounts with the
  `+`/`−` override, the single-ghost-category autofill, backdated-insert slice refresh (Q-UI-5
  decided here). Acceptance via MockMvc in `integrationTest` (Playwright dropped — see §14). Dock
  commit endpoint lives in `operations` (module-cycle precedent from 6d).
- **7c — Edit mode, splits, void.** Edit-in-place with account/date re-threading, `voidTransaction`
  from the dock, the inline split panel with "the rest" defaulting, notes at both levels.
- **7d — Cross-currency entry.** The dock's conversion mode over the already-complete stage-3
  engine (both native amounts entered; rate proposed; FX residual verified end-to-end).
- **7e — Tags.** `tag`/`posting_tag` migration (data-model §10, owned by `categories`), the
  keyboard-first chip field, split inheritance, register display.

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
- **Register follow-ons:** column re-sorting with the balance-hide rule (register §2.7) — deferred
  from stage 7a until missed.
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
- **Testing — browser smoke (Playwright): dropped for the foreseeable future** (owner decision,
  2026-07-05). The money-critical flows the tech-stack doc earmarked for Playwright (transaction
  entry→commit, receipt review→commit, statement match→confirm) are instead covered end-to-end at
  the **controller/htmx acceptance** level via MockMvc in the integration tier — which already
  asserts the rendered `hx-*` wiring, the swap responses, and the resulting balances against real
  Postgres, without the browser-binary/WSL setup cost. Revisit only if a defect surfaces that a
  server-side acceptance test structurally cannot catch (e.g. a real client-JS interaction in one of
  the sanctioned JS leaves). Supersedes the "Playwright arrives at 7b" line in the stage-7 sub-plan
  and the Should-level UI-testing rows in `tech-stack.md`/§15.

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