# Hauptbuch

A self-hosted, single-user double-entry ledger (Microsoft Money replacement). The design docs
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
debt leaves. The **post-to set** is the subset that may be *booked to* — open real posting leaves
only (no closed, no person leaves, no groups), each labelled by its full `Parent - Leaf` path. Every
own-account picker uses it — the dock's Account picker and transfer targets, the receipt paying
account, and Settle-up (issue transaction-register-ui/25); the Closed and All filter tabs reach
the whole read set.
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
a saved one. A Report's page is also where it is edited — there is no separate viewer.
_Avoid_: query, view, analysis, chart (for the spec)

**Preset**:
A Report the application defines in code and always provides — net worth over time, the
category×month matrix, the balance sheet. Non-deletable and not editable in place; changing one's
settings and choosing *Save as new report* is how you get a Report of your own.
_Avoid_: default report, built-in, template, example

**Layout / Frame**:
A **Layout** is a grid of **Frames** (rows × columns), each Frame displaying one Report. The
reporting page has a Layout; the main page has its own 1×1 Layout, so "the one report on the main
page" is not a special case. A Frame shows its Report's **name** as its heading and links to the
Report's page. The reporting page shows its Layout read-only; the Layout is edited on a page of its
own. A Report is *placed in a Frame* — it is never "pinned"; pinning stays exclusive to accounts.
_Avoid_: dashboard, grid, tile, widget, pinned report

**Scope**:
Which kinds of account a report's measures add up — account types, plus whether closed accounts and
`pending_review` transactions are in. Required, because every transaction sums to zero: without a
scope every sum is 0. Scope answers "add up *what*?"; a filter answers "only *which ones*?".
_Avoid_: population, universe, base, perimeter

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

### Recurring

**Recurring template**:
A stored transaction shape — anything the dock can enter, splits and tags included, categories chosen
semantically — plus a schedule that says when it repeats. It produces occurrences; it is not itself a
transaction and moves no balance. Its funding side may be a person, exactly as in the dock (`by Son`):
pocket money is an expense funded by the child's account, so the operator owes the child.
_Avoid_: subscription (a subscription is just a recurring template), schedule (for the whole thing),
standing order, rule, recurring transaction

**Occurrence**:
One dated instance a recurring template produces. Every N days/weeks steps from the start date; monthly
repeats on the start date's day-of-month, falling back to the month's last day where that day does not
exist (31 Jan → 28 Feb → 31 Mar — the anchor never drifts); yearly repeats on the start date's day and
month, a 29 Feb start falling back to 28 Feb in non-leap years. Booking an occurrence yields an
ordinary transaction that remembers which template and occurrence it came from.
_Avoid_: instance, installment, pre-registration
An occurrence is computed from the schedule, never kept as a record of its own.

**Lead time**:
How many days before its date an occurrence is booked (`0` = on the day). The booked transaction
carries the occurrence's date, so with a lead time it sits in the register as a future-dated row.
_Avoid_: notice period, advance, horizon

**Confirmation (auto / review)**:
Per recurring template: `auto` books each occurrence `confirmed`; `review` books it `pending_review`
for the operator to check and Save (which confirms it) — e.g. a foreign-currency charge whose real
base amount is only known from the statement. The operator's choice; a long lead time with `auto`
deliberately puts confirmed future rows in the ledger.
_Avoid_: auto-commit, approval

**End reminder**:
An optional per-template warning on the main page, starting a chosen number of days before the
template's end date and linking to the template. Dismissing it switches it off for that template.
_Avoid_: notice period, notification, renewal reminder

**Booked-through date**:
Per recurring template, the latest occurrence date it has already handled. Each run books every
occurrence after it up to today + lead time, then moves it forward; nothing is ever booked on or
before it, so downtime just means a longer catch-up and a voided occurrence never comes back. Saving
a template pulls it back to no later than yesterday, so a changed schedule or lead time takes effect
at once while past occurrences are never booked retroactively.
_Avoid_: watermark, last run, cursor (in docs and UI)

**Recurring cost summary**:
The fixed table on the recurring page that shows, per month and per year in the base currency, what the
live templates move. It classifies each non-funding leg as expense (broken down by top-level
category), income, or transfer (to an own account or a person), plus a net grand total. It is
calculated from the schedules alone, not from booked postings, so it is not an engine Report.
_Avoid_: subscription report, recurring report, cost report

### Statements

**Statement**:
One uploaded bank file — CSV or PDF — for one account, with its period and optional opening and
closing balances, kept on the Pi as evidence. Worked through on the statement page until green;
there is no reconciliation operation to start or commit.
_Avoid_: bank statement import, reconciliation (for the object), session

**Statement profile**:
How to read one source: a CSV dialect and column map, or a PDF bank's AI note, plus the matching
date window. A CSV profile can read any CSV, not only a bank's — it is the generic CSV import.
_Avoid_: import profile, template, bank format, mapping

**Statement line**:
One booking the bank reports, in the one shape every parser produces: booking date, value date,
amount in the account's currency, optional original amount/currency/rate, counterparty, description,
the bank's own category. The bank's date lives here, never on the transaction.
_Avoid_: bank transaction, row, entry, item

**Match**:
The confirmed 1:1 link between a statement line and a posting — the only stored result of
reconciliation. It exists only while its posting is `reconciled`. What the matcher offers before
confirmation is a **proposal** (exact, amount differs, ambiguous, wrong account).
_Avoid_: link (in UI), pairing, reconciliation (for the link)

**Missing line**:
A statement line with no match and no candidate the operator accepted — a booking the ledger lacks.
Created through the dock embedded on the statement page.
_Avoid_: unmatched line, new transaction, orphan

**Extra**:
A posting on the statement's account, dated in its period, matched to no line of it and not
`reconciled`. A `reconciled` posting is never an extra. A **boundary extra** is one dated in the
window's last (or first) days of the period, labelled "probably on the next statement", and does not
count against green. Computed, never stored.
_Avoid_: unmatched transaction, surplus, discrepancy (for the posting)

**Green**:
A statement whose every line is matched, with no extras beyond boundary extras, and — when it has
balances — no unexplained difference at either end. Computed on every view, never a stored state.
_Avoid_: done, closed, reconciled (for the statement), complete
