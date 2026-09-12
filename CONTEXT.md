# Hauptbuch

A self-hosted, single-user double-entry ledger (Microsoft Money replacement). The five design docs
under `docs/` are authoritative for domain rules; this glossary pins the canonical *terms* so
issues, tests, and discussions don't drift into synonyms.

## Language

### Accounts

**Open / closed**:
Whether an account is currently in use — `account.closed_at` is null (open) or set (closed).
Closing is reversible. A closed account is still **live**; the two axes are orthogonal.
_Avoid_: active/inactive, archived, retired

**Live / soft-deleted**:
Whether an account row still counts at all — `account.deleted_at` is null (live) or set. Integrity
checks and every default read scope to live rows. Distinct from open/closed: a closed account is
live.
_Avoid_: deleted (for the row that is merely closed)

**Read set / post-to set** (register account filter, issue transaction-register-ui/22):
The **read set** is every live own account the register may *view* — open, closed, and per-person
debt leaves. The **post-to set** is the subset that may be *booked to* — open real accounts only
(no closed, no person leaves). The dock's Account picker and transfer targets use the post-to set;
the Closed and All filter tabs reach the whole read set.
_Avoid_: viewable/bookable as nouns, filter set

**Deleted person** (register account filter, issue transaction-register-ui/23):
A soft-deleted `person` row whose per-currency debt leaves are still live — a third state beside
open/closed (an account axis) and live/soft-deleted (a row axis): the *person* is soft-deleted, the
*leaf account* is not. Kept live so an old transaction's person leg still resolves. Surfaces in the
register's Closed and All filter tabs as a person group with a muted "deleted" marker on the toggle
(derived from `person.deleted_at` at render time, never stored); absent from Persons and Last used.
A merged-away person is not one of these — the merge soft-deletes its emptied leaves.
_Avoid_: closed person, archived person, retired person

### Receipts

**Receipt**:
A captured scan of a paper receipt moving through a lifecycle toward (at most) one transaction. A
receipt may die without a transaction; a transaction has at most one receipt.
_Avoid_: attachment, scan, document

**Receipt state**:
The stored lifecycle position of a receipt (new, pre-processed, processing, processed, committed,
discarded, failed). Drives the register filter. Distinct from the workflow step.
_Avoid_: status, stage (for the stored value)

**Workflow step**:
The UI surface currently shown for a receipt (pre-process, process, post-process, confirm). Gated
by state but not the same axis — a step is where you *are*, a state is what the receipt *is*.
_Avoid_: state (for the UI position), phase

**Discarded**:
A receipt deliberately not booked (junk, true duplicate) — kept for the record. Not deleted:
soft-delete is the orthogonal "remove this row" axis.
_Avoid_: deleted, rejected

**Void (badge)**:
The grey "Void" label/dot shown for a `committed` receipt whose linked transaction was voided
elsewhere (e.g. from the register directly) — computed live at render time against the
transaction's own `deleted_at`, never stored on the receipt. Not a `receipt` state; a display
fact layered on top of `committed`. Distinct from voiding itself, the underlying ledger act.
_Avoid_: orphaned, stale, disconnected, voided receipt (the receipt isn't voided — its
transaction is)

### AI parsing

**AI Vocabulary**:
The operator-curated projection of the category taxonomy that receipt parsing may see — per
category an alias, a hide flag, and a category AI note. The only category information ever sent to
an AI provider. Owned by the categories concept, consumed by receipt parsing.
_Avoid_: category list, taxonomy export, custom nodes

**Category AI note**:
Freetext guidance attached to a category, injected into the prompt to steer how items under it are
filed — including instructing per-line tags or a beneficiary ("diesel → tag Car:Audi"). The AI only
echoes names such a note supplies; echoes resolve against live entities or are dropped.
_Avoid_: custom node, term, rule engine

**AI note (per-receipt)**:
Freetext guidance the operator attaches to one receipt before analysis to steer that one parse
("this is fuel"). Travels with the receipt; retained for re-analysis and audit.
_Avoid_: prompt, comment

### Landing page

**Pinned account**:
An `asset`/`liability` account the operator has flagged (`account.show_on_main_page`) to appear in
the landing page's Balances panel with its current balance. Opt-in, per-account, toggled on the
account editor. Closed and soft-deleted accounts never show even while flagged; the flag persists.
_Avoid_: favourite, starred, watched

**Balances panel**:
The landing-page list of pinned accounts — each account's native balance, a base-currency figure
in brackets for non-base accounts, and a base-currency Total row when two or more are pinned. A
convenience readout ("how much money do I have"), not a balance sheet and not net worth. It answers
the *composition* half of that question; the Frame above it (the main page's 1×1 Layout, by default
net worth over time) answers the *change over time* half.
_Avoid_: dashboard, overview, net worth, balance sheet

**Tracking stats**:
The single muted landing-page line above the Balances panel: how long the book has been kept (since
the earliest transaction), the live transaction count, and the analyzed-receipt count with total
receipt-image storage size. Derived aggregates only — never per-transaction detail.
_Avoid_: dashboard, metrics, summary

**Public base URL**:
The URL a phone on the same LAN can reach the app at, ending in a single `/` — derived from the
incoming request (gateway `X-Forwarded-*` headers included, so a path prefix survives) unless
`hauptbuch.public-base-url` overrides it. Unresolvable when the request came in on loopback.
_Avoid_: external URL, host, address, canonical URL

**Phone QR panel**:
The landing-page panel headed "Open on your phone": a QR code of the public base URL plus the same
URL in plain text. A convenience for getting the phone to the capture surface — it carries no
credential and grants nothing the LAN did not already grant.
_Avoid_: pairing, login QR, share link

### Reporting

**Turnover**:
A *flow* measure — the signed sum of postings whose transaction date falls inside the period, valued
posting-by-posting at each posting's own date's rate (data-model §6.1). Aggregates across both axes:
over time and over accounts. The only aggregate offered over turnover is `sum`.
_Avoid_: spend, volume, activity, movement

**Closing balance**:
The only *stock* measure — an account's cumulative position at the end of the period, valued at that
date's rate (mark-to-market, data-model §6.1). Aggregates across accounts (§5's leaves-only rollup)
but **never across time**: last month's closing balance plus this month's is not a quantity. So the
aggregation is fixed — `last` over time, `sum` over accounts — and is never chosen by the operator.
_Avoid_: outgoing balance, ending balance, final balance, balance (unqualified, in a report context)

**Legs**:
Which side of the accounts in scope a turnover measure counts: `debits only`, `credits only`, or
`net`. Independent of account type — spending is a **credit** on a debit card (asset) and on a credit
card (liability) alike, because the stored sign convention (data-model §4) never flips by type. Only
*display* sign flips, and that is §4.1's display rule, not a report setting.
_Avoid_: direction, inflow/outflow, sign, money in/money out

**Report**:
A named, saved specification — dimensions on rows/columns/series, measures, filters, date range,
renderer — plus its own URL. Not the rendered output: the same Report re-run tomorrow shows
different numbers. An unnamed, unsaved spec carried in the query string is still a Report, just not
a saved one.
_Avoid_: query, view, analysis, chart (for the spec)

**Preset**:
A Report the application defines in code and always provides — net worth over time, the
category×month matrix, the balance sheet. Non-deletable and not editable in place; altering one
means copying it to a Report of your own.
_Avoid_: default report, built-in, template, example

**Layout / Frame**:
A **Layout** is a grid of **Frames** (rows × columns), each Frame displaying one Report. The
reporting page has a Layout; the main page has its own 1×1 Layout, so "the one report on the main
page" is not a special case. A Report is *placed in a Frame* — it is never "pinned"; pinning stays
exclusive to accounts.
_Avoid_: dashboard, grid, tile, widget, pinned report

**Measure**:
What a cell counts: a **turnover** or a **closing balance**, each in either the base currency or the
account's own currency — four measures, chosen from a list, laid out along the column axis when more
than one is wanted. Presentation currency is part of the measure, not a report-wide setting.
_Avoid_: metric, value, aggregate, KPI

**Unspecified row**:
The synthetic first child, labelled `(unspecified)`, that appears when a **tag** is expanded on a
report axis — the postings carrying that tag directly, with none of its sub-tags. Exists because tags
are not leaves-only (data-model §10.3). **Accounts and categories never have one**: leaves-only
posting forces a real catch-all child (`Food:General`) instead.
_Avoid_: direct row, other, none, uncategorized, remainder

**Range endpoint**:
One end of a report's date range — either a literal date or an expression of *unit × offset × edge*
(`month, −1, end` = the last day of last month). Both ends are endpoints, which is why a saved Report
stays fresh and why no "include the current period" toggle exists: whether the current month is in
range is simply what the end endpoint says.
_Avoid_: period, range preset, date filter, from/to

**Not a number (`—`)**:
The marker a cell or total carries when the aggregate would be arithmetically meaningless rather
than merely empty: a closing balance summed along the time axis, an account-currency measure spanning
two currencies, a grand total across overlapping tags (data-model §10.4). Distinct from an empty
cell (no postings, rendered blank) and from a real zero (rendered `0,00`).
_Avoid_: n/a, null, error, invalid
