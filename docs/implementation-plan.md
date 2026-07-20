# Hauptbuch — Implementation Plan

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.22
**Date:** 2026-07-20
**Owner:** volkovandr
**Companion to:** `requirements.md`, `tech-stack.md`, `data-model.md`,
`ui-transaction-register.md`, `ui-receipt-processing.md` (the five authoritative design docs)

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
> The cross-cutting decisions in §1 began as «A» working assumptions, listed in §16 with their
> overturn cost; all are realised as of stage 6, but §16 is kept as the record of what was assumed.

**Changelog** — *scope changes only* (§8a): work moved between stages, a decision overturned, an
entity added. Routine implementation lives in git; a completed stage's own description records what
it shipped. "Stage N complete" needs no recap here.
- **v0.22 (2026-07-20):** The **sigil-vs-category-type check leaves 8b.1** (owner call). Implementing
  it uncovered that the dock and the split panel already disagree on what an explicit `+`/`−` means
  (absolute vs flip), with §3.8's prose and §3.5's table documenting one each; it needs its own
  thought-through pass rather than a decision made in passing. Tracked as issue 06 under
  `.scratch/transaction-register-ui/`. Everything else in 8b.1 is unaffected.
- **v0.21 (2026-07-20):** **Person entry redesigned mid-8b**, splitting **8b into 8b.1 / 8b.2**. The
  dock's separate "or person" field is **removed**: the **Account field becomes a typed datalist**
  accepting `for`/`by` sigils, so a person — including a **brand-new** one — is entered there the same
  way as in Category. The category-currency selector generalises to **the transaction currency** (the
  currency of every leg that is not a real account), which is what supplies the currency when the
  funding leg is itself a person. A **`person_leaf` flag** joins `currency_leaf` on `account`
  (person leaves are hidden from the dock's Account picker, the accounts screen, and transfer-target
  resolution — but stay in the register's viewed set per Q-UI-1). Account and person names may no
  longer begin `to `/`from `/`for `/`by `. Split-panel person support becomes **8b.2**; the
  htmx commit-error 500 (register blanks on any error) is **out of scope for stage 8** — a global
  error-handling gap, scheduled between stages 8 and 9.
- **v0.20 (2026-07-19):** **Stage 8 made concrete**, split into **8a–8f**. **Q-UI-1 resolved
  (surfaced)** — a person's debt account is an ordinary `asset`, already in the default register set,
  so a person-funded pure expense appears with the person on the Account side. Person **entry rides
  the transfer path** (`for`/`by` keyword sigil, mirroring `to`/`from`); person data shape is
  standalone per-currency leaves with **no parent account**, grouped by `account_owner`. The
  equal/shares/exact split calculator, per-group "simplify debts", groups/trips, and debts-over-MCP
  are **deferred** to `potential-feature-ideas.md`.
- **v0.19 (2026-07-11):** **FX gain/loss auto-booking retired** (data-model §6.3); **stage 7d
  re-scoped** into 7d.0–7d.3 with a category-currency selector and per-currency amounts balanced in
  base. 7d.0 additionally **un-seeds `FX gain/loss`** — with no code path resolving it by name it is
  no longer a system leaf (dropped from the V2 seed and `createCurrency`), just a lazy user category.
- **v0.15 (2026-07-05):** Stage 7 made concrete, split into **7a–7e** (tags last; cross-currency
  after edit/splits; keyboard-first piecewise; sorting deferred to §14). Payee gains city/country +
  a seeded `country` table at 7b.
- **v0.11 (2026-07-04):** Inserted **6c — Category deletion**; the former 6c (currency-list editor)
  renumbered to **6d**.
- **v0.8 (2026-07-01):** Stage 6 split into **6a/6b/6c** (later renumbered by v0.11).
- **v0.6 (2026-06-30):** `web` module (UI shell only) added to the §3 map — feature controllers stay
  in their own modules.
- **v0.2 (2026-06-25):** Base currency moved from config into a DB **`settings` entity**
  (write-once); multi-currency **fully live from stage 3** (no single-currency phase). §14 reduced to
  a flat, unordered backlog inventory.
- **v0.1 (2026-06-25):** Initial plan — staged increments + backlog; module-first from day one;
  three-tier test strategy; the stage-3 service framed as invariant-upholding domain ops, not CRUD.
- **Stages marked complete** (routine, no recap): 1 (v0.3), 2 (v0.4), 3 (v0.5), 4 (v0.6), 5 (v0.7),
  6a (v0.9), 6b (v0.10), 6c (v0.12), 6d (v0.14), 7a (v0.16), 7b (v0.17), 7c (v0.18), 8a (v0.21),
  8b.1 (v0.22).

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
the conditional sum-to-zero invariant (both branches) and frozen `base_amount` on genuinely
two-currency postings (data-model §6.4 — *not* a new mechanism, just the cross-currency path). A
cross-currency transaction must **balance in base from its entered legs**; the engine books no FX
residual (data-model §6.3, decision 2026-07-11 — `FX gain/loss` is a manual leg; the earlier
auto-booking is retired in stage **7d.0**). The `exchange_rate` table exists from stage 3 with manual
rows and the carry-forward `rate_as_of` lookup.

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
Repo, license, `README.md`, `CLAUDE.md` (the Modulith rule stated with teeth — enforced by
`ApplicationModules.verify()`, not prose), the design docs moved into `/docs`, `.gitignore`.

### Stage 2 — Skeleton ✅ **complete**
An enforced-but-empty modular monolith that boots and tests green. Choices that outlive the code:
Java 25 / Spring Boot 4.1; module-first packages (§1.1) as empty stubs + an **open** `shared` kernel
(the money type); Spring Modulith scoped to **`-starter-core`** with `verify()` in the unit suite —
the `-jdbc` event/outbox starter is deliberately unused (events deferred, CLAUDE.md §3/§8); the three
Gradle test suites (§1.5) with Testcontainers reuse; **Joda-Money** (T1/P-5) behind a thin
`MoneyFactory`; dev Postgres on host port **15432**.

### Stage 3 — The transaction core (the engine, fully multi-currency) ✅ **complete**
The uniform posting model, correct and tested, with **no UI**. Delivered: the write-once `settings`
entity (§1.3, base currency a precondition for recording); Flyway migrations for
`currency`/`account`/`transaction`/`posting`/`exchange_rate`/`payee` with seeded system accounts;
JdbcClient records + repositories; the domain-ops service (§1.4:
`recordTransaction`/`voidTransaction`/`editTransaction`) validating conditional sum-to-zero before
insert; cross-currency freezing `base_amount` on non-par conversions (data-model §6.4); the
invariants as `sqlLogicTest`s (data-model §8); the windowed running-balance query incl. the
backdated-insert case.
> The residual auto-booking to `FX gain/loss` shipped here is **retired in 7d.0** (data-model §6.3,
> 2026-07-11).

### Stage 4 — UI container (the shell) ✅ **complete**
The server-rendered scaffold: base Thymeleaf layout, htmx wired, the dense spreadsheet-like house
style, nav shell; the **keyboard-layer leaf** scaffolded (the UI's main maintainability risk); the
shared **German number-formatting** utilities (§1.6, register §2.9).

### Stage 5 — Settings UI ✅ **complete**
The first real screen over the §1.3 entity: base currency set once on a fresh book then **read-only**
(write-once guard confirmed end to end); an editable display name feeding the "Hello, %name%"
landing.

### Stage 6 — Accounts & categories (with opening balances) ✅ **complete**
The reference data the register needs, plus the equity plumbing; built as four ordered sub-stages,
each green and demoable. The `operations` module is born here.
> **Module-boundary decision (carried from stage 3):** `Account` + a read-only `AccountRepository`
> were born in `ledger` because the engine reads accounts before `accounts` exists. **6a moved
> ownership of `Account` and the account table's writes into `accounts`**; `ledger` now depends on
> `accounts`' public read type. `verify()` stayed green throughout — the repositories were
> package-private, so nothing cross-module was ever exposed.

- **6a — Accounts + opening balances.** ✅ Account management UI (any seeded currency; stored two-tone
  hue per account, register §2.8); an opening balance posts as a real balanced transaction against the
  per-currency `Opening Balances` leaf.
- **6b — Categories + subdivision.** ✅ Category management over the *same* account table (income/
  expense, data-model §3.2). `operations` gains **`subdivideAccount`** (leaf → parent, postings
  reassigned to an `Uncategorized` sibling), triggered **implicitly** by creating a child under a
  posted-to leaf. Categories take **no currency** — every leaf is base-currency; per-currency leaves
  arrive lazily with real usage (data-model §6.5).
- **6c — Category deletion.** ✅ A category is truly *deleted* (unlike accounts, which close/reopen):
  the user picks a **surviving leaf** that receives all reassigned postings; deleting a parent
  cascades the **whole subtree**. `deleteCategory` in `operations` reuses 6b's reassignment repo in
  reverse (many → one). **Load-bearing rule:** "leaf" is judged **after** the deletion, so a node
  whose only children are inside the deleted subtree is a valid target (deleting `M&Ms` lets `Sweets`
  receive the postings); the target may not be the deleted node or any descendant (data-model §5).
- **6d — Currency-list editor.** ✅ An "Add currency…" affordance in **every** picker (base-currency
  picker included). `createCurrency` (an `operations`, not CRUD) provisions only the currency's
  **system** backing leaf (`Opening Balances <CODE>`), mirroring the V2 seed (the `FX gain/loss <CODE>`
  leaf 6d originally provisioned was **retired in 7d.0** — no longer a system leaf, data-model §6.3);
  it does **not** back-fill category leaves — those stay lazy (data-model §6.5). Interaction is htmx +
  native `<dialog>`, **no bespoke JS** (§1.6). **Two gotchas worth remembering:** htmx attributes emit
  via `th:attr` (no htmx Thymeleaf dialect — `th:hx-*` silently drops), and the modal backdrop is a
  sibling `<div>`, not a `::before`. The htmx-driving controller lives in `operations`, not `ledger`,
  to avoid a `ledger ↔ operations` cycle (the 6d precedent reused by stage 7).

**Done when (6a–6d):** accounts (any currency) and categories can be created/managed; an account opens
with a starting balance that is a real balanced transaction; subdivision works; a category (leaf or
whole subtree) is deletable with its postings reassigned to a chosen surviving leaf; a new currency
is addable from any picker, provisioned with its backing leaf and pre-selected.

---

> **From here, plan is rough by intent.** Stage 6 puts a usable UI in front of the owner; expect the
> ordering and detail below to be revised once that feedback arrives.

### Stage 7 — Transaction register & entry dock ✅ **complete**
The two central surfaces from `ui-transaction-register.md`: the newest-at-bottom register and the
persistent bottom entry/edit dock. UI inspiration mock-ups in `docs/pic/register-*.png`. Six ordered
sub-stages, each green and demoable; keyboard-first as each piece lands (Q-UI-2 decided piecewise,
never retrofitted):

- **7a — Register, read-only.** ✅ **complete.** The list over a register query + the stage-3
  running-balance SQL rebound to a real repository; date/account/payee filters (column re-sorting
  deferred to §14); zebra, currency display, muted `pending_review`.
- **7b — Entry dock, simple transactions.** ✅ **complete.** Payee picker with create-new parsing
  (payee gains city/country + a seeded ISO-3166 alpha-3 `country` list), category picker with
  create-new and the lazy per-currency-leaf routing (data-model §6.5), sign-free amounts with the
  `+`/`−` override, the single-ghost-category autofill, backdated-insert slice refresh (Q-UI-5
  decided here). Acceptance via MockMvc in `integrationTest` (Playwright dropped — see §14). Dock
  commit endpoint lives in `operations` (module-cycle precedent from 6d).
- **7c — Edit mode, splits, void.** ✅ **complete.** Edit-in-place with account/date re-threading,
  `voidTransaction` from the dock, the inline split panel with "the rest" defaulting, notes at both
  levels.
- **7d — Cross-currency entry.** ✅ **complete.** Re-scoped into four packages (2026-07-11):
  **7d.0** retire FX auto-booking (engine); **7d.1** category-currency selector + cross-currency
  single-line entry; **7d.2** cross-currency in splits; **7d.3** transfers (single + split). Detail
  in the stage-7 plan.
- **7e — Tags.** ✅ **complete.** `tag`/`posting_tag` migration (data-model §10, owned by
  `categories`), the keyboard-first chip field, split inheritance, register display.
- **7f — Edit for cross-currency & transfer shapes.** ✅ **complete.** Carved out past 7e
  (owner-confirmed 2026-07-14): re-editing cross-currency single-line, single-line transfers, and
  splits containing a transfer leg (same/cross-currency). Every shape the entry surface creates is
  now editable. Detail in the stage-7 plan.

### Stage 8 — People & per-person debts
The `debts` module (data-model §7, §3.3) and its two surfaces: the register (entry + display) and a
new **People page**. The load-bearing decision: **a person leg is just a transfer to/from that
person's account**, so entry and display ride the existing transfer + split machinery — the only
net-new engine step is **auto-provisioning** the person's account on first reference. Cross-cutting
decisions, settled before slicing:

- **Data shape.** A person is a `person` row plus one standalone per-currency `asset` **leaf** each
  (**no parent account**), linked by one `account_owner` row per leaf (§3.3 as sketched). Grouping is
  by the `account_owner → person` link, never by name, so nothing ever rolls up across currencies.
  Leaf names are cosmetic (`personal.<CUR>`); every display resolves the person's name via that link.
  **Rename touches `person.name` only**; ids never move. **Duplicate person names are allowed**,
  disambiguated in pickers (as payees already are).
- **Entry = transfer.** The reserved `for`/`by` sigils sit beside the existing `to`/`from` transfer
  keywords and mean the same thing in **either** picker: `for <person>` puts the person's leg on the
  **debit** side (`→ Person` — they owe you), `by <person>` on the **credit** side (`Person →` — you
  owe them). **Both pickers are typed datalists** and both accept a person, including a brand-new one;
  the Account field carries the person as the **funding** leg (register §2.6 pattern 3), the Category
  field as the counterpart. There is **no separate person field** — see §3.3/§3.5 of the register doc
  for the field behaviour, and §3.8 for the direction rule below.
- **The sigil adds no direction machinery — it is a checked assertion.** Where the counterpart is a
  **category**, direction is already decided by §3.8 (the category type sets the funding leg's sign; a
  negative amount flips it) and the sigil is verified against it: agreement commits, contradiction is
  **blocked with an explanation** and never silently corrected. Where the counterpart is a person or an
  account, the sigils/keywords *are* the direction source, exactly as a transfer's `to`/`from` already
  is. This makes `by Max` + expense and `for Max` + income ordinary, and their opposites legal **only**
  with a negative amount (a storno) — one rule, not a table of cases.
- **The currency selector generalises to the transaction currency** — the currency of every leg that
  is **not** a real account. With a real funding account it keeps today's behaviour (defaults to that
  account's currency; an override declares the transaction cross-currency). With **no** real account
  (a person funding a category, or a person-to-person debt transfer) it sets **every** leg, so the
  transaction is single-currency, and it defaults to the involved person's leaf currency when that is
  unambiguous, else to base. This is what makes a brand-new person enterable in the Account field:
  provisioning finally has a currency source. It is shown unless **every** leg is a real account.
  Note the consequence, accepted deliberately: an expense a person funded is denominated in the **debt**
  currency, not the spending currency — the expense leg answers *"where did this debt come from"*, not
  *"what did this cost in EUR"*.
- **Person leaves are hidden from pickers, not from the register.** A `person_leaf` flag on `account`
  (mirroring `currency_leaf`) excludes them from the dock's **Account** picker, the **accounts
  management** screen, and **transfer-target** resolution — three places they leak today. They stay in
  the register's **default viewed set** (Q-UI-1, below) and gain a place in its **filter**, listed and
  displayed as `Max (EUR)`; the cosmetic leaf name never reaches the UI. The flag is required rather
  than an `account_owner` lookup because `debts` already depends on `accounts` — the reverse edge
  would be a module cycle.
- **Reserved name prefixes.** An **account** name (hence a category name) and a **person** name may not
  begin `to `, `from `, `for ` or `by ` (case-insensitive), so a sigil is never ambiguous with a name.
  Enforced in the **service layer only** — this is a UI-parser convenience, not a data-model invariant,
  so it earns no DB constraint. Accepted cost: names like "For Kids" must be written "Kids".
- **Display.** Person legs are ordinary `asset` legs → **two-row symmetry** (register §2.4); the
  funding/edit-anchor leg of a multi-own-leg transaction is the **most-negative own leg**
  (generalises the shipped transfer sign rule to any number of legs); arrow-chips render per register
  §2.6. **Q-UI-1 is resolved — surfaced:** person accounts are `asset`, hence already in the default
  viewed set, so a person-funded pure expense appears with the person on the Account side (its running
  balance is a real balance, exactly like a credit card's).
- **Auto-provisioning** at commit: ensure the person, ensure a `personal.<CUR>` leaf, link via
  `account_owner`. Reviving a soft-deleted person (a name that matches **only** a soft-deleted row) is
  a **confirmed** action, never silent.

Six ordered sub-stages, each green and demoable (7-series cadence):

- **8a — `debts` foundation + minimal People page.** ✅ **complete.** V7 migration (`person`,
  `account_owner`), records, repositories including the **per-person per-currency signed-balance**
  query (`sqlLogicTest`), the **provisioning** op (ensure person + leaf + link, with revival),
  `PersonService` (create / rename / soft-delete with a **zero-balance guard**), and a bare People
  page (list, create, rename, soft-delete — names only). `ApplicationModules.verify()` green.
- **8b.1 — Person entry, single line.** ✅ **complete.** `for`/`by` in **both** pickers with inline
  create + confirmed revival, auto-provision at commit, same- and cross-currency. Converts the dock's
  **Account** field from a `<select>` to a typed datalist (pre-filled from the previously entered
  transaction — see below) and **deletes** the "or person" field with its
  `/people/resolve-account` endpoint. Adds the transaction-currency generalisation, the
  `person_leaf` flag and its three picker exclusions, and the reserved name-prefix rule. Label
  tooltips. The register's own display — including its filter — is untouched here and stays 8c's.

  **The sigil-vs-category-type check is deferred out of 8b.1** (owner, 2026-07-20). Writing it
  surfaced that the simple dock (`signedAmount`, **absolute**) and the split panel
  (`signedContribution`, **flip**) already implement *opposite* meanings for an explicit `+`/`−`, and
  §3.8's prose and §3.5's sigil table document one each. The check cannot be written until that is
  settled, and the owner chose to re-think and test it properly rather than resolve it in passing.
  Written up as `.scratch/transaction-register-ui/issues/06-explicit-sign-dock-vs-split-inconsistent.md`.

  Person leaves are in the register's **viewed set** as the cross-cutting bullet specifies (Q-UI-1,
  surfaced) but out of the **pickers** — the Account picker, the account filter, and the transfer
  targets. An earlier attempt to keep them out of the viewed set too (reasoning that
  `RegisterRowRenderer` cannot resolve a person's name until 8c) was **reverted after owner testing
  on 2026-07-20**: it made a `for Max` entry show only one of its two rows, and a `by Max` funded
  expense show nothing at all, which reads as a transaction that failed to book. Cosmetically-named
  but visible beats invisible; 8c replaces the `personal.<CUR>` name with `Max (EUR)`.

  Relatedly, the dock no longer serialises the **resolved default account set** as an explicit
  `viewAccountId` filter — on the default view every option reads as selected, so carrying them
  froze the default into a real filter and dropped any account not in the picker (a person's leaf)
  from the view on the next commit.

  **Sticky entry defaults** (`potential-feature-ideas.md`, Register UX) land here because the field
  conversion forces the question: a committed dock echoes back its **date and funding account** rather
  than resetting them. A **person** funding leg is never sticky — it falls back to the last real
  account — since an unnoticed sticky person would silently book a debt and possibly provision a leaf.
  A fresh page load keeps today's fallback (today's date, first account).
- **8b.2 — Person entry, splits.** The same `for`/`by` capability per split line — multi-person
  attribution ("€31,50: €21,50 my food, €10 for Max", register §2.6). Threads person fields through
  `SplitForm`/`SplitFormBinder`, `SplitLineDraft`, `DockSplitService`, `SplitLineAmounts`,
  `SplitPanelAssembler`, `SplitLineView`, `SplitEditService` and `split-panel.html` the way
  `lineTransferDirection` already is. The 8b.1 direction check and currency default apply **per line**.
  Split from 8b.1 to keep each diff hand-reviewable (§0) and to prove the Account conversion in real
  use before threading it through the split machinery.
- **8c — Register display.** Two-row symmetry with person legs, arrow-chip rendering (Account/Category
  per §2.6), the most-negative-own-leg funding rule, Q-UI-1 surfacing. Includes the **name
  resolution** that 8b.1 deliberately leaves out: `RegisterRowRenderer` has no person awareness today,
  so a person leg renders its cosmetic `personal.<CUR>` leaf name in both columns. Until this lands,
  8b.1 is verifiable by its tests and by the stored postings, **not** by reading the register.
  Also **new here:** the register **filter** lists people as `Max (EUR)` — individually selectable and
  combinable with each other and with real accounts. (Only the pre-filtered *link* from the People page
  was previously planned, in 8d; ticking people yourself was not.) The filter block growing bulky with
  many people is acknowledged and deferred — collapsing it is a later UX pass.
- **8d — People balances.** Net-balance column, expand → per-currency signed balances, and a "view in
  register (pre-filtered to this person)" link, on the existing People page.
- **8e — Settle-up button.** A per-person×currency launcher: pick account + date + amount(s) (one or
  two fields by currency match, §3.8a), direction auto-detected from the balance sign, committed
  through the existing path — a pre-scoped transaction, not new engine.
- **8f — Merge.** Reassign-based person merge (`PostingReassignmentRepository`) — the way to remove a
  **non-zero** person by folding their postings, per currency, into another person.

Deferred to `potential-feature-ideas.md`: the equal/shares/exact split calculator, per-group
"simplify debts", groups/trips, and MCP exposure of debts.

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
  the sanctioned JS leaves). Supersedes the earlier "Playwright arrives at 7b" plan and the
  Should-level UI-testing rows in `tech-stack.md`/§15.

---

## 15. Testing strategy — the rationale

The three-tier mechanics (which suite runs against what, and which tier a given repository method
belongs in) are the operational rules in **CLAUDE.md §6** — not restated here. This section keeps only
the *why* behind the shape:

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
- **No separate UI tier:** browser smoke (Playwright) is dropped (§14); money-critical flows are
  covered at the controller/htmx acceptance level (MockMvc) in the integration tier. Do **not**
  unit-test templates — server-rendering means most "UI logic" is backend logic already covered above.

---

## 16. Working assumptions folded into this plan (all now realised)

| # | «A» working assumption baked into this plan | Overturn impact |
|---|----------------------------------------------|-----------------|
| P-1 | Multi-currency fully live from stage 3 (cross-currency, frozen `base_amount`, `exchange_rate` table); `FX gain/loss` is a manual leg, no auto-booking (2026-07-11 decision); only ECB feed automation + held-balance revaluation arrive with the reports/integration that consume them | Re-scopes stage 3 vs §14 |
| P-2 | Root `settings` entity in the DB holds base currency (write-once, required before any transaction); entity born at stage 3, UI at stage 5; now ratified into the data-model doc (§3.8) | Small schema; entity defined |
| P-3 | Stage 6 covers accounts **and** categories **and** opening balances; `operations` born here (subdivision) | Re-scopes stage 6 |
| P-4 | Stage-3 service is invariant-upholding domain ops, not generic CRUD | Naming/shape of the `ledger` service |
| P-5 | Money type picked at stage 2 (T1); test suites named at stage 2 | Low-risk, local |

*(P-1–P-5 are all realised as of stage 6; retained as the record of what was assumed and its
overturn cost, per the "why survives" rule.)*