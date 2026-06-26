# Hauptbuch — Requirements Document

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.4
**Date:** 2026-06-19
**Owner:** volkovandr
**Type:** Self-hosted, single-user, web-based personal finance application

> This is a living document. Items marked **«Q»** are open questions/decisions needed.
> Items marked **«A»** are working assumptions you can confirm or overturn.
> Priorities use MoSCoW: **Must**, **Should**, **Could**, **Won't (this version)**.

**Changelog**
- **v0.4 (2026-06-19):** Added an explicit UX & interaction model (Money-style dense inline
  master-detail, numbers-first analytics, visual restraint). Specified the category×month matrix
  report and the consolidated-balance timeline with trend line. Added the monthly narrative
  report, first-class data-management operations (merge/reassign), and an MCP server for
  AI-agent access (Claude Code) with the associated security/privacy notes.
- **v0.3:** DB + files native on Pi; PDF-first statements; scheduled+trend forecasting;
  format-agnostic import; multi-profile books; mobile interface; Telegram quick-capture bot.
- **v0.2:** AI policy resolved; double-entry; Money-export import; budgets on expense taxonomy;
  per-person auto-managed shared-debt ledger.
- **v0.1:** Initial draft.

---

## 1. Vision

A private, self-hosted replacement for Microsoft Money that keeps a complete, queryable record
of personal finances across multiple accounts and currencies, answers analytical questions,
forecasts the near future, and removes manual data entry through AI-assisted ingestion of
receipts and bank statements. The interface is **dense, inline, and numbers-first** in the
spirit of classic Money — not a modal-heavy, low-density, over-graphical modern app. Everything
runs on the owner's Pi with data in the owner's PostgreSQL. Quick capture is possible from a
phone or a secured Telegram bot, and an optional MCP server lets an AI agent (e.g. Claude Code)
answer questions and perform bulky structural operations by command.

---

## 2. Guiding principles & global constraints

| ID | Principle | Priority |
|----|-----------|----------|
| GP-01 | **Self-hosted only.** Runs on the owner's machine or a Raspberry Pi on the home network. | Must |
| GP-02 | **Data sovereignty.** All financial data lives in the owner's PostgreSQL on the Pi. No financial records stored on third-party servers. | Must |
| GP-03 | **Browser-based**, responsive front end for desktop and mobile. | Must |
| GP-04 | **Keyboard-first UX** on desktop; every common action completable without a mouse. | Must |
| GP-05 | **Single user.** No concurrent multi-user accounts or roles. (Profiles, §5.14, are sequential contexts.) | Must |
| GP-06 | **Privacy by default.** Data only leaves the network through explicit, opt-in channels: (a) a document sent to a hosted AI for parsing, (b) the optional Telegram bot (§5.16), and (c) the optional MCP/AI-agent access (§5.19). Each carries its own notes, and exposure via (c) scales with the query. The database itself never leaves the Pi. | Must |
| GP-07 | **Data portability.** Full export at any time; no lock-in. | Should |

### 2.1 AI / cloud policy — **RESOLVED**

Hosting the *accounting data* in the cloud is unacceptable, but **temporarily uploading a single
document for parsing is acceptable**. Local LLMs were evaluated and are not good enough; quality
floor is **Anthropic Claude Sonnet 4.6** (current-generation hosted models preferred). The parser
is pluggable (ARCH-03) for upgrades. Note that the MCP/agent feature (§5.19) is a *separate,
broader* exposure discussed there. See NFR-09.

---

## 3. Architecture & deployment requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| ARCH-01 | App deliverable as containers (Docker / docker-compose). Final packaging decided at tech-stack selection. | Should |
| ARCH-02 | **PostgreSQL runs natively on the Pi**; schema versioned with migrations. | Must |
| ARCH-03 | AI document-parsing behind a **pluggable provider interface** (≥ Sonnet 4.6), swappable for upgrades. | Should |
| ARCH-04 | Minimal authentication (single login) to avoid open LAN access. | Should |
| ARCH-05 | Optional HTTPS via reverse proxy for access beyond localhost. | Should |
| ARCH-06 | Exchange rates from a configurable feed (lookup only); cached; manual override always possible. | Should |
| ARCH-07 | **Receipt scans / statements on the Pi filesystem**, retained and linked to transactions; partitioned per profile (§5.14). | Must |
| ARCH-08 | AI calls send only the document + parsing instructions — never the database or balances. | Must |
| ARCH-09 | **Format-agnostic ingestion** via a canonical internal representation; all importers target it; importers added as needed without touching the core. | Must |
| ARCH-10 | **Profile = separate PostgreSQL database** (§5.14), selected via configuration; no cross-profile mixing. | Should |
| ARCH-11 | Telegram bot (if enabled) is a thin client with minimal exposure (§5.16, NFR-11). | Could |
| ARCH-12 | **Domain operations are first-class** in the backend and callable by both the UI and the MCP server — the same validated, audited operation regardless of caller (§5.18, §5.19). | Should |

---

## 4. Core data model (conceptual) — double-entry **CONFIRMED**

Double-entry bookkeeping. Conceptual entities: **Account**, **Transaction**, **Split/line item**
(with category, tags, and an optional **beneficiary**), **Category** (hierarchical, shared with
budgets), **Tag/Label** (many-to-many), **Currency** + **Exchange rate** (+ base currency),
**Payee/Merchant**, **Recurring template**, **Subscription**, **Person (contact)** with an
auto-managed signed receivable/payable balance, **Shared-expense group/trip**, **Attachment**
(on the Pi), **Holding/Position**, **Profile** (own database).

---

## 5. Functional requirements

### 5.0 UX & interaction model (Money-style) — **NEW**

The interaction model is a hard product constraint, derived from what works in Money and what
fails in apps like ezBookkeeping.

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-UX-01 | **Inline master-detail editing.** A transaction list where selecting a row reveals an editable detail panel (bottom or side); fields are edited **in place**. No modal dialog for routine entry/edit. | Must |
| FR-UX-02 | **High information density.** Transactions render as **thin, single-line rows**; maximize how many are visible at once; avoid oversized padding, cards, or 10-row pages that force scrolling. | Must |
| FR-UX-03 | **Numbers first.** Every analytic shows actual figures as text. Charts are supplementary and are **never the only way to read a value** (no hover-to-reveal-the-number). | Must |
| FR-UX-04 | **Visual restraint.** Clean, spreadsheet-like, information-dense aesthetic over decorative/"cartoonish" visuals. | Should |
| FR-UX-05 | **Keyboard throughout.** Select, edit, save, move to next, and create — all by keyboard, consistently across list and detail (ties to NFR-01). | Must |
| FR-UX-06 | **Inline new-transaction entry** directly in the list (append row), no modal. | Should |

### 5.1 Core ledger & accounts

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-CORE-01 | Record income, expense, and transfer transactions with date, amount, currency, account(s), payee, category, tags, note. | Must |
| FR-CORE-02 | Multiple accounts of different types (checking, savings, credit card, cash, etc.). | Must |
| FR-CORE-03 | **Transfers between accounts** as a single logical operation, including cross-currency. | Must |
| FR-CORE-04 | **Transaction splits** across multiple categories/tags/amounts/beneficiaries. | Must |
| FR-CORE-05 | Balances correct at any point in time; running balances visible. | Must |
| FR-CORE-06 | Edit history / audit trail; soft-delete rather than hard-delete. | Should |
| FR-CORE-07 | Fast, fuzzy search and filtering across all transaction fields. | Must |
| FR-CORE-08 | Bulk edit (re-categorize, tag, etc.) on selected transactions. | Should |

### 5.2 Multi-currency

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-CUR-01 | Each account and transaction carries its native currency. | Must |
| FR-CUR-02 | Configurable base currency for consolidated net-worth and reporting. | Must |
| FR-CUR-03 | Store the exchange rate used per cross-currency transaction. | Must |
| FR-CUR-04 | Historical rates retained so past reports stay accurate. | Should |

### 5.3 Recurring transactions

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-REC-01 | Recurring templates with flexible schedules. | Must |
| FR-REC-02 | Auto-generate **pre-registered** transactions ahead of time. | Must |
| FR-REC-03 | Pre-registered items confirmed/matched on actual occurrence (§5.8). | Should |
| FR-REC-04 | Handle variable amounts — estimate now, correct on confirmation. | Should |

### 5.4 Categories & tags

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-TAG-01 | Hierarchical categories, one per transaction/split. **Single taxonomy shared with budgets.** | Must |
| FR-TAG-02 | Many-to-many **tags** on transactions and/or splits. | Must |
| FR-TAG-03 | Analytics sliceable by any tag (`Car: Passat`; `Trip to Prague 2026`). | Must |
| FR-TAG-04 | Multiple tags on one expense (fuel tagged with the car *and* the trip). | Must |
| FR-TAG-05 | Rules engine to auto-apply categories/tags. | Should |

### 5.5 Subscriptions manager

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-SUB-01 | Dedicated view of all subscriptions / recurring services. | Must |
| FR-SUB-02 | Per-subscription metadata (price, cycle, next renewal, account, currency, start date, category, cancellation notes). | Should |
| FR-SUB-03 | Modeled as recurring transactions + metadata, flowing into the ledger and analytics. | Should |
| FR-SUB-04 | Upcoming-renewal overview and total monthly/annual cost (base currency). | Should |
| FR-SUB-05 | Optional renewal/price-change reminders. | Could |

### 5.6 Shared "between-friends" debts — per-person auto-managed ledger

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-DEBT-01 | One signed running balance **per person, per currency**, auto-provisioned, never hand-created. | Must |
| FR-DEBT-02 | **Beneficiary per line item** ("paid for Max", "paid for Max and Ben", "split between me, Max and Ben") with split methods (equal/shares/exact). | Must |
| FR-DEBT-03 | When you paid: others' portions auto-post as receivables; your share stays a normal expense. | Must |
| FR-DEBT-04 | When someone else paid for you: posts what you owe (sign flips). | Must |
| FR-DEBT-05 | Per-person balance nets both directions automatically — no owed-to/owed-by accounts, no manual transfers. | Must |
| FR-DEBT-06 | Multi-currency per person. | Must |
| FR-DEBT-07 | Optional groups/trips, integrated with tags. | Should |
| FR-DEBT-08 | "Settle up" posts one real transaction and moves the balance toward zero. | Must |
| FR-DEBT-09 | Backed by auto-managed double-entry receivable/payable accounts. | Must |
| FR-DEBT-10 | Per-group "simplify debts" suggestions. | Could |
| FR-DEBT-11 | Beneficiary marking available in the receipt-review step (§5.7) and via the Telegram bot (§5.16). | Should |

### 5.7 Receipt ingestion (AI) — *killer feature*

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-RCPT-01 | Upload a scan/photo (image or PDF), including from a phone camera (§5.15). | Must |
| FR-RCPT-02 | AI parses merchant, date, total, tax, and **line items**. | Must |
| FR-RCPT-03 | **Mandatory review/confirm** before commit. | Must |
| FR-RCPT-04 | Line items become transaction splits (mappable to categories/tags). | Should |
| FR-RCPT-05 | Review step supports **per-line-item beneficiary assignment** (§5.6). | Should |
| FR-RCPT-06 | Original scan retained on the Pi and linked. | Must |
| FR-RCPT-07 | Handle German receipts and VAT lines (MwSt/USt 19%/7%), EUR by default. | Should |
| FR-RCPT-08 | Learn merchant/item → category mappings over time. | Could |

### 5.8 Bank statement reconciliation (AI) — PDF-first

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-STMT-01 | Upload a statement as **PDF** (primary) or CSV (recent-12-months shortcut). | Must |
| FR-STMT-02 | AI extracts transactions, including from **historical PDFs**. | Must |
| FR-STMT-03 | **Match** statement lines against existing transactions (manual, receipt-parsed, pre-registered). | Must |
| FR-STMT-04 | Flag unmatched statement lines; offer to create them. | Must |
| FR-STMT-05 | Flag ledger transactions with no statement match. | Should |
| FR-STMT-06 | Manual override of any match; nothing committed without confirmation. | Must |
| FR-STMT-07 | Mark reconciled transactions; record reconciliation points. | Should |

### 5.9 Analytics & reporting — **numbers-first, Money-style**

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-ANA-01 | "Where did my money go" — spend by category/tag/payee over a period, with figures shown. | Must |
| FR-ANA-02 | Period comparisons: month vs last month; month vs same month last year. | Must |
| FR-ANA-03 | Category trends over time. | Must |
| FR-ANA-04 | Arbitrary slicing by tag (per-car, per-trip). | Must |
| FR-ANA-05 | Net worth across all accounts and currencies (base currency). | Should |
| FR-ANA-06 | Export reports (CSV; optionally PDF). | Could |
| FR-ANA-07 | **Primary report — category × month matrix.** Rows = top-level categories (expandable to subcategories); columns = months; **income block above, expense block below**, balance-sheet/P&L style; **every cell shows the number**. | Must |
| FR-ANA-08 | **Restrained** in-cell visualization only — optional small sparkline or trend arrow per row; nothing more (no large/cartoonish charts). | Could |
| FR-ANA-09 | **Consolidated-balance timeline** — total balance across accounts over time as a line, **with an added trend line** (an improvement over Money, which lacked one). This is the one graphical report relied upon. | Must |
| FR-ANA-10 | **Drill-down** from any matrix cell to its underlying transactions. | Should |

### 5.10 Forecasting — scheduled + trend

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-FC-01 | Project balances from scheduled recurring + subscriptions. | Must |
| FR-FC-02 | Extrapolate variable categories from history. | Should |
| FR-FC-03 | Add manual planned items and see the impact. | Should |
| FR-FC-04 | Forward running-balance line so discretionary headroom is obvious (e.g. "+1000 next month → 500 to spare"). | Should |
| FR-FC-05 | Scenario modeling — **not** in this version. | Won't (this version) |

### 5.11 Investment / stock tracking (nice-to-have)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-INV-01 | Manually enter/update positions (ticker, quantity, cost basis, current value). | Could |
| FR-INV-02 | Positions contribute to net worth. | Could |
| FR-INV-03 | No live market integration. | Won't (this version) |

### 5.12 Migration & import — format-agnostic core

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-IMP-01 | Import via a **canonical internal representation**; source formats handled by pluggable importers. | Must |
| FR-IMP-02 | Money-history importer once the export format is chosen (QIF/OFX/CSV candidates). | Must |
| FR-IMP-03 | Map accounts/categories/payees/transfers/splits with a review step before commit. | Must |
| FR-IMP-04 | Idempotent import — no duplicates on re-run. | Must |
| FR-IMP-05 | Generic CSV importer for ongoing use. | Should |

### 5.13 Budgets — on the expense taxonomy

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-BUD-01 | Budgets per **existing** category (and/or tag), per period (monthly default). | Must |
| FR-BUD-02 | **No separate budget taxonomy** — same categories/tags as actuals. | Must |
| FR-BUD-03 | Automatic actual-vs-budget tracking, remaining/overspend per period. | Must |
| FR-BUD-04 | Optional rollover per budget. | Should |
| FR-BUD-05 | Projected end-of-period vs budget (budget × forecast). | Could |

### 5.14 Profiles / multiple "books"

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-PROF-01 | Multiple profiles, each its **own PostgreSQL database**, fully isolated. | Should |
| FR-PROF-02 | Create a new **empty** profile without affecting others. | Should |
| FR-PROF-03 | Switch active profile via configuration (and/or selector); no mixing. | Should |
| FR-PROF-04 | Attachments partitioned per profile (ARCH-07). | Should |
| FR-PROF-05 | Per-profile backup/restore (`pg_dump` per database). | Should |
| FR-PROF-06 | Profiles are sequential single-user contexts, not concurrent multi-user (GP-05). | Must |

### 5.15 Mobile interface

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-MOB-01 | Responsive web UI for core viewing and quick entry on a phone. | Should |
| FR-MOB-02 | **Receipt capture from the phone camera** → upload → AI parse → review. | Should |
| FR-MOB-03 | Touch-friendly on mobile; keyboard-first remains the desktop concern. | Should |

### 5.16 Telegram quick-capture bot (secured, optional)

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-TG-01 | Telegram bot as a thin client for **quick capture** (transactions, shared-debt entries, receipt photos). | Could |
| FR-TG-02 | Natural-language entries ("borrowed 10 EUR from Max") create the right item, feeding the shared-debt ledger. | Could |
| FR-TG-03 | Captured items land **review-pending** and are confirmed later in the web UI. | Should |
| FR-TG-04 | **Strict security:** allow-listed Telegram user ID(s); secrets in config; rate-limited. | Must (if built) |
| FR-TG-05 | **Minimal exposure:** no full balances/history; write-mostly. | Should |

**Privacy note:** Telegram routes messages through its servers; keep to low-sensitivity capture.

### 5.17 Monthly narrative report — **NEW**

A short, end-of-month write-up that's pleasant to read and grounded in the numbers — e.g.
"last month had a record-high electricity bill," biggest expenses, notable category swings.

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-RPT-01 | Auto-generate an end-of-month summary in readable prose, surfacing notable items (records, spikes, biggest expenses, month-vs-history comparisons). | Should |
| FR-RPT-02 | Light, engaging tone, but **strictly grounded in actual figures** — no invented claims. | Should |
| FR-RPT-03 | Generated from **aggregates/derived stats**; if AI-assisted (NFR-09), send summary data, not the raw ledger, to limit exposure. | Should |
| FR-RPT-04 | Browsable history of past monthly reports. | Could |

### 5.18 Data-management operations — **NEW**

First-class, validated, transactional operations for structural edits that are bulky in
conventional UIs. Exposed to both a minimal UI and the MCP server (§5.19, ARCH-12). Examples:
"merge all accounts related to Ben and Mary (they married)", "remove the Milk category and move
its transactions to Other Food".

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-DM-01 | Domain operations: merge categories, merge payees, merge contacts/people, merge accounts, bulk re-tag/re-categorize, rename, move-and-delete. | Should |
| FR-DM-02 | Each operation is **atomic, validated, logged in the audit trail, and reversible** (or preview + confirm). | Should |
| FR-DM-03 | Exposed via a minimal UI **and** the MCP server; bespoke rich UIs are intentionally minimized since the agent path covers complex cases. | Should |

### 5.19 MCP server / AI-agent access — **NEW (opt-in)**

Expose an MCP server so an AI agent (e.g. Claude Code) can answer questions about the data and
run the §5.18 operations by command — avoiding the need to build heavy admin UIs.

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-MCP-01 | An MCP server exposing the system to an AI agent. | Could |
| FR-MCP-02 | Exposes **structured domain tools** — read/query tools for analytics, and a curated set of mutation tools (the §5.18 operations). **No raw SQL.** | Should (if built) |
| FR-MCP-03 | Mutations are validated, logged, reversible, and support **preview/dry-run + confirm** before applying. | Should (if built) |
| FR-MCP-04 | **Access control:** runs locally; requires authentication; configurable scope (read-only vs read-write); can be **disabled entirely**. | Must (if built) |
| FR-MCP-05 | Natural-language commands map onto domain tools ("merge accounts for Ben and Mary"; "remove Milk → Other Food"). | Could |

**Privacy note (important):** Unlike single-document parsing, an AI agent answering a question
reads whatever data it needs into the model provider's context **at query time**. A question like
"where did my money go this year?" can pull a large, unpredictable slice of the ledger off the
network. This is a broader exposure than receipt parsing. Mitigations: keep it opt-in, make the
exposed scope configurable, prefer read-only by default, require confirmation for mutations, log
everything, and allow full disabling. See Q11.

---

## 6. Non-functional requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-01 | **Keyboard-first** (desktop): command palette (Ctrl/Cmd-K), keyboard nav, rapid keyboard-only entry, in-app shortcut docs. | Must |
| NFR-02 | Responsive on a Pi for a single user (snappy, virtualized lists) and usable on mobile (§5.15). | Should |
| NFR-03 | **Backups**: documented PostgreSQL backup/restore; one-click data + attachments export; per-profile. | Must |
| NFR-04 | **Security**: login required; secrets (AI keys, DB creds, Telegram token, MCP auth) in config/env, never plaintext in DB; attachments access-controlled. | Should |
| NFR-05 | **Portability**: full open-format export; no lock-in. | Should |
| NFR-06 | **Reliability**: reversible/tested migrations; no silent data loss; idempotent imports; reversible bulk operations. | Must |
| NFR-07 | **Observability**: clear logs for AI parsing, reconciliation, and agent-initiated operations. | Should |
| NFR-08 | **Internationalization**: EUR/German formatting and German-language documents; base currency configurable. | Should |
| NFR-09 | **AI parsing quality floor**: provider ≥ Claude Sonnet 4.6; default current-generation hosted Anthropic model; local-only insufficient. | Must |
| NFR-10 | **Profile isolation & fresh start**: new empty profile + switch back with no mixing; per-profile backup. | Should |
| NFR-11 | **Telegram hardening** (if built): allow-listed users, review-pending capture, rate limiting, minimal exposure, secrets in config. | Must (if built) |
| NFR-12 | **MCP/agent safety** (if built): structured tools only (no raw SQL), all mutations logged + reversible with preview/confirm, access-controlled, disablable, configurable read/write scope. | Must (if built) |

---

## 7. Out of scope (explicitly will NOT build)

- Built-in browser with news and ads.
- Direct integration with banking apps / bank APIs / aggregators (Plaid, etc.).
- **Concurrent** multi-user support (sequential profiles are allowed; simultaneous access is not).
- Live/automatic stock-market price integration.
- A `.mny` binary parser — import via Money's own export instead.
- Local-LLM document parsing as a supported path (NFR-09).
- Forecasting scenario modeling (FR-FC-05) — this version.
- **Modal-dialog-based** transaction entry — rejected in favor of inline master-detail (FR-UX-01).
- **Heavy bespoke admin UIs** for structural edits — intentionally minimized in favor of §5.18
  operations + the MCP/agent path (§5.19).

---

## 8. Open questions summary

| # | Question | Status |
|---|----------|--------|
| Q1 | AI approach / hosted parsing acceptability | **Resolved** — hosted OK; ≥ Sonnet 4.6; local insufficient |
| Q2 | Deployment (Docker, Pi roles) | **Resolved** — Docker OK for app; DB + files native on Pi |
| Q3 | Double- vs single-entry | **Resolved** — double-entry |
| Q4 | Shared-debt settlement | **Resolved** — auto-managed per-person signed balances |
| Q5 | Statement formats | **Resolved** — PDF primary; CSV last-12-months |
| Q6 | Forecasting ambition | **Resolved** — scheduled + trend |
| Q7 | Migrate Money history | **Resolved** — yes, via Money export |
| Q8 | Budgets | **Resolved** — yes, on the expense taxonomy |
| Q9 | Money export format | **Deferred** — format-agnostic core; importer later |
| Q10 | Telegram external routing acceptable | Assumed yes; see §5.16 note |
| Q11 | **MCP/agent: accept that queries send data subsets to the model provider at query time?** And default scope — read-only, or read-write with confirmation? | **Open** |
| Q12 | Monthly narrative report: aggregates-only (max privacy) vs richer AI input? | Open — defaulting to aggregates-only (FR-RPT-03) |

---

## 9. Appendix — Existing solutions landscape (as of June 2026)

No single self-hosted app covers the full list. Useful references (and what to borrow vs. avoid):

- **Firefly III** — strongest core (double-entry, multi-currency w/ auto FX, recurring, tags +
  categories + budgets, reconciliation, REST API). No AI parsing, no Splitwise-style debts.
- **ezBookkeeping** — feature-rich and Pi-friendly with AI receipt recognition and Firefly/GnuCash
  import; **good idea source**. But **avoid** its interaction model: modal-on-doubleclick entry,
  low-density lists, and over-graphical/number-hiding analytics — the opposite of §5.0.
- **Maybe Finance / Sure**, **Wealthfolio** — net worth + investments + "ask AI" / portfolios.
- **TaxHacker** — self-hosted AI receipt/invoice parsing.
- **Wallos** — self-hosted subscription manager.
- **Spliit / SplitPro / Rosplata** — self-hosted Splitwise; all model debts the "old" way; the
  per-person auto-netting line-item design (§5.6) is the part none bundle into a ledger.
- **GnuCash**, **Beancount + Fava** — double-entry power users; no built-in AI; not
  browser-keyboard-first.
- **Actual Budget** — envelope budgeting; weaker analytics/forecast; not Postgres.

**Differentiators that justify a custom build:** one unified Pi-hosted Postgres system; a dense,
inline, numbers-first Money-style UX; AI statement reconciliation from historical PDFs; multi-tag
per-car/per-trip analytics; the category×month matrix + consolidated-balance timeline with trend
line; budgets on the same taxonomy as expenses; the auto-managed per-person shared-debt ledger;
multi-profile books; mobile + Telegram quick capture; a monthly narrative report; and an MCP
server letting an AI agent answer questions and perform structural edits by command.