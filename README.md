# Hauptbuch

> A self-hosted, single-user, web-based **Microsoft Money replacement**: a double-entry ledger with
> multi-currency support, AI receipt ingestion, and an MCP surface — built to run on a Raspberry Pi.

**Status:** Pre-implementation. The design is documented and ratified; the codebase is being built
in staged increments (see `docs/implementation-plan.md`). There is nothing to build or run yet.

---

## What this is

Hauptbuch (German for *general ledger*) is a private replacement for Microsoft Money, for one person
who wants a complete, queryable record of their finances across many accounts and currencies — and
who is unwilling to hand that data to a cloud service. Everything runs on the owner's own machine,
with the data in the owner's own PostgreSQL.

It aims to keep what worked about Money and fix what didn't:

- **Dense, inline, numbers-first UI** in the spirit of classic Money — thin single-line rows, figures
  always visible, keyboard-first entry — explicitly *not* a modal-heavy, low-density, over-graphical
  only-mobile-friendly modern app.
- **A correct double-entry engine** underneath: every transaction's postings sum to zero, by
  construction, across any mix of account types and currencies.
- **Multi-currency done properly**, including the subtle parts — frozen historical conversion rates,
  mark-to-market net worth, and unrealized FX that lives only in net worth (not in the spending
  report).
- **AI that removes data entry, not data sovereignty:** receipts and bank statements are parsed by a
  hosted AI, but **only the single document being parsed ever leaves the network** — never the ledger,
  balances, or database.

## Why it exists

No existing self-hosted finance app covers the whole list the owner cares about, and the closest ones
get the *interaction model* wrong (modal entry, low-density lists, charts that hide the actual
numbers). The justification for a custom build is the combination, in one Pi-hosted Postgres system,
of:

- a dense, inline, **numbers-first** Money-style UX;
- AI **receipt ingestion** and AI **statement reconciliation from historical PDFs**;
- **multi-currency** with proper FX valuation, plus budgets on the same taxonomy as actuals;
- an **auto-managed per-person shared-debt ledger** (Splitwise-style, but folded into the ledger);
- multi-tag **per-car / per-trip** analytics, the category × month matrix, and a consolidated-balance
  timeline with a trend line;
- a monthly **narrative report**, and an **MCP server** letting an AI agent answer questions and
  perform bulky structural edits by command.

See `docs/requirements.md` §9 for the full landscape of existing tools and what is borrowed vs.
avoided from each.

## What it is built on (in brief)

A Spring Boot **modular monolith** — one deployable app whose internal module boundaries are *real and
enforced by the build*, not just conventions. The stack is chosen so the codebase stays navigable and
correct by a human alone:

- **Java + Spring Boot**, with **Spring Modulith** enforcing module boundaries.
- **Native SQL via `JdbcClient` + Java `record`s** — no ORM, no jOOQ; the SQL is visible in the repo.
- **Flyway** migrations against **PostgreSQL**; all tests run against real Postgres via Testcontainers.
- **Server-rendered Thymeleaf + htmx** — no SPA, no npm, no bundler, no TypeScript. Bespoke JS is
  confined to two isolated leaves (the keyboard layer and the Cropper.js image component).
- AI parsing behind a pluggable provider interface (Anthropic Claude); image pre-processing is fully
  manual and entirely client-side.

The full rationale for every choice — and the alternatives rejected — is in `docs/tech-stack.md`.

---

## Repository map

```
docs/                          The authoritative design — read before changing anything
├── requirements.md            Scope, UX philosophy, the full feature list and priorities
├── tech-stack.md              Every technology decision, with its rationale and rejected alternatives
├── data-model.md              The double-entry engine, currency/FX, per-person debts, tags; invariants
├── ui-transaction-register.md The register and the entry/edit dock; display & entry rules
├── ui-receipt-processing.md   Receipt lifecycle, the four-step workflow, the receipt schema sketch
└── implementation-plan.md     The staged build sequence and the backlog — start here for what's next

CLAUDE.md                      Operational guide for AI-assisted work (how to build correctly here)
README.md                      This file
```

## Where to start

- **To understand the product and *why* it exists** → `docs/requirements.md`, then this README's
  "Why it exists" above.
- **To understand the technology choices** → `docs/tech-stack.md`.
- **To understand the money model** (the part easiest to get subtly wrong) → `docs/data-model.md`.
  Beware the load-bearing facts: `+` = debit / `−` = credit and postings sum to zero; categories are
  *backed by* accounts (no separate category table); per-person debts are signed accounts; and the
  two deliberately-different valuation rules for flows vs. balances.
- **To understand what gets built next, and in what order** → `docs/implementation-plan.md`.

The design docs are **authoritative for every product and domain decision.** Each records not just the
*what* but the *why*, so the reasoning survives long after the code is written. When a doc and the code
disagree, that is a bug to reconcile — not a detail to paper over.

## Design principles (the through-line)

One constraint governs everything: **the codebase must stay navigable and correct by a human alone,
without AI assistance.** This is why the stack favours a statically-typed language the owner writes
fluently, a conventional discoverable framework, visible SQL over hidden ORM magic, architecture
boundaries enforced by the build, and UI complexity quarantined into small, well-bounded leaves.
Boring, uniform, idiomatic code is the feature.
