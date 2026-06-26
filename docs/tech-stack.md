# Hauptbuch — Tech Stack & Architecture Decisions

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.2
**Date:** 2026-06-26
**Owner:** volkovandr
**Companion to:** `requirements.md` (v0.4)

> This document records *technology* decisions and their rationale. Requirements live in the
> companion document; this one says **how** they will be built and **why** each choice was made.
> Decisions are recorded with their reasoning so the *why* survives long after the *what* is code.

**Changelog**
- **v0.2 (2026-06-26):** **AI-assisted cropping removed entirely** (§5.2) — image pre-processing is
  now **purely manual**, with no AI in the image step; the AI sees only the finished, human-cropped
  image (tried and rejected: validating an AI crop takes longer than just cropping). Added
  `categories` to the module map (§3.1) and corrected the `ledger` module description from "splits"
  to **postings** (postings are the bookkeeping records; "splits" is UI/UX vocabulary).
- **v0.1 (2026-06-20):** Initial stack. Backend (Java + Spring Boot, JdbcClient, no ORM, Flyway,
  Testcontainers). Spring Modulith for enforced module boundaries. Thymeleaf + htmx UI with an
  isolated client-side image-editing component. Image processing kept 100% client-side; AI crop
  suggestions are advisory only. Running-balance correctness on backdated inserts called out.

---

## 0. The overriding constraint

**The codebase must stay navigable and correct without AI-agent assistance.** Claude Code is used
heavily, but the proven failure mode is that as a codebase grows, the human loses the thread and
can no longer reason about it. Every technology choice below is filtered through one question:
*does this keep the project comprehensible and steerable by a human alone?* Where a choice trades
raw capability for legibility and enforced structure, legibility wins.

This is why the stack favours: a statically-typed language the owner writes fluently; a
conventional, discoverable framework; visible SQL over hidden ORM magic; architecture boundaries
that are **enforced by the build** rather than merely documented; and UI complexity quarantined
into small, well-bounded leaves.

---

## 1. Stack at a glance

| Layer | Choice | Priority |
|-------|--------|----------|
| Language | **Java** (not Scala, not Python) | Must |
| Framework | **Spring Boot** | Must |
| Module structure | **Spring Modulith** — enforced boundaries + generated docs | Must |
| DB access | **Spring `JdbcClient` + Java `record`s** — native SQL, no ORM | Must |
| Migrations | **Flyway** (plain versioned `.sql`) | Must |
| Money type | **Joda-Money** or JSR-354 — never `double`/`float` | Must |
| Database | **PostgreSQL** (native on the Pi, per ARCH-02) | Must |
| Backend testing | **JUnit + AssertJ + Testcontainers** (real Postgres) — TDD | Must |
| UI | **Thymeleaf + htmx** (server-rendered) | Must |
| UI — image editing | Isolated client-side component (**Cropper.js** + canvas) | Should |
| UI — keyboard layer | Small isolated vanilla-JS / Alpine.js module | Must |
| UI testing | **Playwright** smoke tests on money-critical flows only | Should |
| AI parser | Anthropic API via official Java SDK / Spring AI, behind ARCH-03 interface | Should |
| MCP server | Spring (Spring AI MCP) wrapping the §5.18 domain operations | Could |
| Packaging | Docker / docker-compose for the app (ARCH-01); Postgres native | Should |
| Runtime | JVM on the Pi; GraalVM native image held as a future escape hatch | — |

---

## 2. Backend

### 2.1 Language — Java (over Scala and Python)

**Decision:** Java.

**Rationale.** The owner writes Java/Scala and Spring Boot fluently and reads but does not write
Python. The constraint in §0 makes fluency decisive: in a language you only read, you cannot
*intervene* — you are reduced to accepting or rejecting agent output, which is precisely how a
codebase drifts beyond comprehension. In Java the owner can stop the agent, rewrite a class by
hand, and set the pattern to follow.

- **Static types + IDE** give find-usages, safe rename, and call-hierarchy navigation — the exact
  tools for re-orienting in code you've lost track of. These do not meaningfully exist in Python.
- **Spring Boot's verbosity is an asset here:** it is conventional and discoverable, so the
  structure resists the ball-of-mud tendency of agent-generated code.
- **Agent consistency:** Claude Code produces more uniform, idiomatic output in Java/Spring than
  almost anything else, because the framework is rigid and heavily represented — less room to
  invent five ways to do one thing.

**Scala was rejected** despite owner fluency: its expressiveness is the enemy of agent
consistency (plain vs. Cats-Effect vs. ZIO drift). Boring, uniform Java keeps the agent on-rails.

### 2.2 Database access — `JdbcClient` + records, no ORM

**Decision:** Spring `JdbcClient` (Spring 6.1+ / Boot 3.2+), mapping rows into Java `record`s.
**No ORM. No jOOQ** (explicitly rejected). Literal native SQL only.

**Rationale.** The owner is a strong SQL/PostgreSQL practitioner; visible native queries beat
hidden ORM magic for comprehension — the data flow is right there in the repository. The analytics
in particular (the category×month matrix, the consolidated-balance timeline with trend line) are
window-function and `generate_series` territory that is pleasant to write as raw SQL and that an
ORM would only obscure. `JdbcClient` adds no build step and is "native queries" in the most
literal sense.

### 2.3 Migrations — Flyway

Plain, versioned `.sql` files. Natural fit for an SQL-first owner and for ARCH-02 (schema
versioned with migrations). Supports the reversible/tested-migration requirement (NFR-06).

### 2.4 Money type — never floating point

Use **Joda-Money** or the **JSR-354 Monetary API**. For a double-entry ledger, floating-point
money is non-negotiable to avoid. It also reads better at call sites.

### 2.5 Testing — TDD against real PostgreSQL

**Decision:** JUnit + AssertJ, **Testcontainers** spinning up real PostgreSQL per test run. TDD
throughout the backend.

**Rationale.** Because the queries are native Postgres SQL (window functions, `generate_series`),
they **must** be tested against real Postgres — **H2 will not do**, as it does not support the
constructs the analytics rely on. Tests run on the dev machine, not the Pi, so the Pi's modest
resources are irrelevant to test cost. Tests double as the living specification: when the owner
returns to a module after time away, the tests state what it does.

---

## 3. Module structure — Spring Modulith

**Decision:** Adopt **Spring Modulith** from day one, used for **enforced boundary verification
and generated documentation**. The event-publication / outbox features are **deliberately not
used in v1.**

**Why this is the centrepiece of the §0 constraint.** Spring Modulith builds a *modular monolith*:
one deployable app, but with internal module boundaries that are **real and enforced**, not folder
conventions one hopes are respected. A top-level package under the application root *is* a module;
by default a module's sub-packages are internal, and other modules may only use its top-level
(public) types.

**What it buys, concretely:**

1. **Boundaries are a test.** A single `ApplicationModules.of(App.class).verify()` test fails the
   build on any cross-module reach-in or any dependency cycle between modules. The agent
   **cannot** quietly violate the architecture, because the build goes red. After months away, the
   structure is *guaranteed* intact — not "intact if nobody cut a corner."
2. **Generated, always-correct documentation.** Modulith emits module diagrams (PlantUML/C4) and a
   per-module canvas, derived from verified code, so it never goes stale — exactly the
   re-orientation aid the owner needs.
3. **Fast slice tests** — spin up a single module in a test rather than the whole context.

**Deliberately deferred:** the Event Publication Registry / transactional-outbox machinery. It is
useful for decoupling but adds event tables, async semantics, and eventual consistency that a
single-user Pi app does not need; direct service calls are simpler and easier to follow. The
machinery remains available later if ever wanted.

**`CLAUDE.md` implication.** Rather than *describing* the architecture and hoping the agent honours
it, instruct the agent that boundaries are enforced by `ApplicationModules.verify()` and to run the
module test after changes. The rule has teeth — a constraint, not a convention.

**Commitment required up front.** Modulith assumes **module-first** packaging (by feature), not
layer-first (`controllers/`/`services/`). This aligns with the vertical-slice structure already
intended, but must be adopted from the start — retrofitting later is painful.

### 3.1 Proposed module map (initial)

Organize by feature (vertical slices), each a top-level package = one Modulith module:

```
com.<app>.finance
├── ledger          core double-entry: accounts, transactions, postings, transfers, balances
├── accounts        account definitions & types
├── categories      hierarchical categories + tags (shared taxonomy, §5.4)
├── debts           per-person auto-managed shared-expense ledger (§5.6)
├── receipts        receipt ingestion + review (§5.7)
├── statements      bank-statement reconciliation (§5.8)
├── budgets         budgets on the expense taxonomy (§5.13)
├── recurring       recurring templates + subscriptions (§5.3, §5.5)
├── analytics       matrix report, balance timeline, narrative report (§5.9, §5.17)
├── forecasting     scheduled + trend projection (§5.10)
├── importer        format-agnostic import via canonical representation (§5.12)
├── operations      first-class data-management ops: merge/reassign/etc. (§5.18)
└── mcp             MCP server exposing the operations + read tools (§5.19)
```

Public types per module are the entry points other modules may call; everything else lives in
internal sub-packages. The `operations` module is the single home for the §5.18 domain operations,
called by **both** the UI and the MCP server (ARCH-12) — same validated, audited, reversible
operation regardless of caller.

---

## 4. UI

### 4.1 Approach — Thymeleaf + htmx (server-rendered)

**Decision:** Thymeleaf templates rendered by the Spring app, with **htmx** for interactivity
(fragment swaps for inline master-detail, append-row entry, report drill-down). **No SPA**
(React/Svelte/etc.).

**Rationale.** This keeps the UI inside the owner's ecosystem — no npm, no bundler, no TypeScript,
no second runtime to maintain without the agent (which would *double* the §0 risk). Most of the app
is easily server-rendered: the category×month matrix is a server-rendered `<table>`; master-detail
panels and drill-downs are fragment swaps; the balance timeline is a single server-generated SVG
chart. Claude Code handles htmx well.

A React/Svelte SPA was **rejected**: more capable for the dense grid, but it is an entire second
ecosystem the owner does not know and could not maintain agent-free — the exact stated fear.

### 4.2 The transaction register — insert performance & running balances

A server-rendered, htmx-driven register is well within htmx's comfort zone. Two clarifications that
shaped the design:

- **Insert is not a full re-render.** On new-transaction submit, the server returns **only the new
  row's HTML** (`hx-swap="afterbegin"` into the `<tbody>`); htmx inserts **one DOM node**. The
  thousands of existing rows are untouched — no reparse, no subtree re-layout. Insert cost is
  effectively constant in list length.
- **The view is always bounded.** The natural viewing unit is a month / an account / a filter
  result — hundreds of rows, not "everything since 2009." Both initial render and any worst-case
  re-fetch operate on hundreds of rows (single-digit ms). Even a lazy "re-fetch the current bounded
  view" fallback is fast because the view is bounded.

**The one genuine wrinkle — running balances on backdated inserts.** With newest-first sort and a
per-row running balance (balance *as of* that row's date):

- **Newest transaction (the 90% case):** goes on top; only the top balance changes; all earlier
  rows are unaffected. **One-row insert is fully correct and trivial.**
- **Backdated transaction:** every row *more recent* than it (the rows **above** it) has its
  running balance shifted. Handle via, in order of preference:
  1. **Re-render the affected slice** (insertion point → top) and swap it, e.g. htmx out-of-band
     swaps (`hx-swap-oob`) carrying both the new row and the corrected rows above it.
  2. **Re-fetch the current bounded view** (brute force, still fast — hundreds of rows).
  3. Show running balance only in a per-account reconciliation view (sidesteps it; probably too
     austere).

> **Test requirement:** backdated-insert balance correctness is easy to get subtly wrong (a
> register that shows wrong balances until reload). Write an explicit test for it.

**Long unbounded lists** (if ever deliberately opened): use htmx **infinite-scroll chunks**
(`hx-trigger="revealed"`), not virtualization. The DOM grows on scroll; initial render stays
bounded. With normal date/account filtering this is rarely reached — it is an escape hatch, a few
attributes, not a framework.

### 4.3 Keyboard layer

The keyboard-first requirements (FR-UX-05, NFR-01: arrow-key nav, enter-to-edit, next-row, Ctrl-K
command palette) are the one part htmx cannot do cleanly. Implement as a **small, self-contained
vanilla-JS or Alpine.js module** — a few hundred lines, well-isolated, reviewed carefully because
it is the UI's main maintainability risk. It is a *leaf*, not a framework threading through the app.

### 4.4 UI testing

Server-side rendering means much of what would be "UI logic" in an SPA is **backend** logic already
covered by TDD. For the genuine UI, write a thin layer of **Playwright** smoke tests only on flows
where breakage costs money-correctness or real time: receipt review → commit, statement match →
confirm, transaction entry. **Do not unit-test templates.** This is the deliberate 80/20 — lighter
UI coverage is accepted (per owner's stance).

---

## 5. Receipt image handling

**Principle (from experience):** automating image transforms is unreliable — auto-crop/deskew works
in demos and mangles real receipts (long, crumpled, angled, dark backgrounds), and a silent bad
"fix" surfaces only as garbage parse output two layers later. Therefore: **image pre-processing is
fully manual.** Nothing is committed to the image without explicit human action, and **no AI is
involved in the image step at all** (see §5.2).

### 5.1 Editing is 100% client-side

Rotate / crop / brightness / contrast happen entirely in the **browser canvas** — **no server-side
image processing**, no image libraries on the Pi, no Python, no Pi CPU spent on image math. Rotation
is a transform, crop a clipped redraw, brightness/contrast a CSS-filter live preview plus a final
canvas pixel pass when baking the output. The corrected JPEG/PNG is what gets sent to the AI parser;
the Pi never does image work.

**Component:** **Cropper.js** for crop/rotate/zoom/flip (battle-tested, free), with a small
self-written brightness/contrast preview + final canvas pass on top. (Pintura is a polished all-in-one
alternative but commercial — adopt only if the assembled Cropper.js experience proves annoying.)
This is the one unavoidable bit of bespoke JS, but it is an **isolated leaf** (one component, one
job, known library) — wrap it so the rest of the htmx UI just sees "a corrected image came back."

### 5.2 Cropping is manual only — no AI in the image step

**There is no AI involvement in cropping or any other image transform.** An earlier idea — have the
parser return a suggested bounding box / rotation to seed the Cropper.js box — was **tried and
rejected**: manual cropping is trivial (a drag, well under a second), whereas *validating and
correcting* an AI's crop decision takes **longer** than just doing it, and it adds a round-trip and a
failure mode for no benefit. So the human crops/cleans the image by hand, and the AI sees only the
finished, human-cropped image. (This matches the receipt-processing doc, §3.2/§6.1.)

### 5.3 Recommended flow — capture on phone, edit on PC

Treated as the **default**, not a fallback: the phone is a poor place for precise cropping. Phone
uploads the raw photo → it lands **review-pending** (same pattern as Telegram capture, FR-TG-03) →
the owner opens it on the PC and does crop/brightness in the full Cropper.js view with mouse +
keyboard, then commits. **The original untouched scan is always retained on the Pi** (ARCH-07 /
FR-RCPT-06); the edited image is a derived artifact, so any bad crop is recoverable by re-editing
from the original.

---

## 6. AI parser & MCP server (later features)

Both stay in Java — no Python anywhere.

- **Parser (ARCH-03):** Anthropic API via the official Java SDK or Spring AI, behind the pluggable
  provider interface. Sends only the document + parsing instructions, never the database or
  balances (ARCH-08). Quality floor ≥ Claude Sonnet 4.6 (NFR-09).
- **MCP server (§5.19):** Spring (Spring AI's MCP-server support) exposing **structured domain
  tools** — read/query tools and the curated §5.18 mutation operations, **no raw SQL** (FR-MCP-02).
  Same Spring beans wrapping the same `operations` module the UI uses (ARCH-12). Mutations:
  validated, logged, reversible, preview/dry-run + confirm (FR-MCP-03).

> Spring AI's MCP support and the Anthropic Java SDK move quickly; verify their current state when
> these (late "Could"/"Should") features are actually reached. The architectural fit is clean.

---

## 7. Packaging & runtime on the Pi

- **App:** Docker / docker-compose (ARCH-01). **PostgreSQL native on the Pi** (ARCH-02), not
  containerised.
- **Footprint:** a Spring Boot app idles ~250–400 MB and starts in a few seconds; a Pi 4/5 with
  4 GB+ runs it comfortably for one user. Footprint is explicitly not a primary concern.
- **Escape hatch (not a starting choice):** GraalVM native image (Spring Boot supports it) cuts
  memory and gives near-instant startup, at the cost of build complexity and some reflection-config
  friction with JDBC. Start on the JVM; keep native image in reserve.

---

## 8. Decisions & rejections summary

| Area | Decision | Rejected | Why |
|------|----------|----------|-----|
| Language | Java | Scala | Scala's expressiveness hurts agent consistency |
| Language | Java | Python | Owner reads but doesn't write Python; can't intervene |
| DB access | `JdbcClient` + records | ORM (JPA/Hibernate) | Hidden magic; visible SQL aids comprehension |
| DB access | `JdbcClient` + records | jOOQ | Explicitly rejected by owner |
| Test DB | Real Postgres (Testcontainers) | H2 | H2 lacks window functions / `generate_series` |
| Modules | Spring Modulith (boundaries + docs) | Modulith event registry | Outbox complexity unneeded for single-user v1 |
| UI | Thymeleaf + htmx | React/Svelte SPA | Second ecosystem the owner can't maintain agent-free |
| Long lists | Bounded views + htmx chunks | Virtualization | Unneeded; view is naturally bounded |
| Image edit | Client-side canvas (Cropper.js) | Server-side processing | Keeps Pi clean; no Python; isolated leaf |
| Image edit | Manual only, client-side | AI-assisted / auto-applied transforms | Auto image fixes are unreliable; validating an AI crop takes longer than just cropping |

---

## 9. Open / deferred technical questions

| # | Question | Status |
|---|----------|--------|
| T1 | Money type: Joda-Money vs JSR-354 | Open — pick one early; low-risk either way |
| T2 | Backdated-insert balance update: OOB slice swap vs bounded re-fetch | Decide during register build; OOB preferred, re-fetch acceptable |
| T3 | Alpine.js vs plain vanilla JS for the keyboard layer | Open — both fine; keep it small & isolated |
| T4 | Cropper.js (free, assembled) vs Pintura (paid, all-in-one) | Default Cropper.js; revisit only if assembly annoys |
| T5 | Spring AI MCP support / Anthropic Java SDK current state | Verify when §5.19 is reached (late feature) |
| T6 | GraalVM native image | Deferred escape hatch; start on JVM |