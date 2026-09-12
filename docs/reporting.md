# Hauptbuch — Reporting: the Report Engine, Renderers & Layouts

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.1
**Date:** 2026-09-12
**Owner:** volkovandr
**Companion to:** `requirements.md` (§5.9, FR-ANA-01–10, FR-REP-01–14),
`data-model.md` (§4 signs, §5 leaves-only, §6.1 the two valuation rules, §10 tags),
`ui-transaction-register.md` (the drill-down handoff, the filter component),
`implementation-plan.md` (§3) and `implementation-plan-reporting.md` (the slicing),
`docs/adr/0001-generic-report-engine.md`

> This document records the **design of reporting** — one generic report engine and the objects
> around it (Report, Preset, Layout, Frame) — together with the reasoning, in keeping with the house
> rule that the *why* must survive long after the *what* is code.
>
> Scope note: it **supersedes** the previous plan of two hand-built reports. `requirements.md`
> FR-ANA-07/08/09 described a fixed category×month matrix, a fixed balance timeline, and an explicit
> prohibition on anything more graphical; the owner overturned that after returning to Microsoft
> Money and finding its *reporting breadth* to be the thing worth reproducing. The matrix and the
> timeline survive as **Presets** of the engine, not as bespoke screens. ADR-0001 records why.
>
> Terminology is pinned in `CONTEXT.md` (§Reporting) and is not redefined here.

---

## 1. The shape, in one paragraph

A **Report** is a specification: dimensions placed on **rows**, **columns** and **series**, a list of
**measures**, a **scope**, **filters**, a **date range**, and a **renderer**. The engine turns that
spec into a **grid** — a two-dimensional array of cells plus the axis trees that label it — and a
renderer draws the grid as a table, a line chart, a bar chart or a pie. Everything else in this
document exists to make exactly one thing impossible: a cell that shows a number which is not a
number. The engine knows which aggregates are arithmetically meaningful and refuses the rest.

---

## 2. Four objects

- **Report** — a saved spec with a name and a URL. Not the output: the same Report re-run tomorrow
  shows different figures. An unsaved spec lives in the query string and is still a Report.
- **Preset** — a Report the application defines **in code**, always present, non-deletable, not
  editable in place. `/reports/preset/net-worth` always resolves; altering a Preset means copying it
  to a Report of your own. This is deliberate: the main page's default report must not be
  destroyable by a botched edit.
- **Layout** — a grid of Frames (rows × columns), each Frame displaying one Report.
- **Frame** — one cell of a Layout.

The reporting page has a Layout. **The main page has its own 1×1 Layout**, so "the one report on the
main page" needs no special case — the same picker, the same rendering path, a different Layout row.
That the main page holds *zero or one* report is then a property of its Layout's dimensions, not a
rule in code.

---

## 3. The grid

Three axes, each taking **an ordered list of dimensions**:

| Axis | Takes | Renders as (table) | Renders as (chart) |
|------|-------|--------------------|--------------------|
| **rows** | 0–2 dimensions | row groups, nested | **small multiples** — one plot per row value, stacked |
| **columns** | 0–2 dimensions | column groups, nested | the **x-axis** (pie: the slices) |
| **series** | 0–1 dimension | *(unused)* | the plotted lines / bar groups / legend |

**A combination is nesting, not a cartesian product.** `rows = [Tag, Category]` renders each tag as a
group whose expansion reveals its category breakdown — the same tree the hierarchy machinery (§9)
already walks, with the levels supplied by two dimensions instead of one parent chain. Flat cartesian
rows (`Prague / Food`, `Prague / Fuel`, `Vienna / Food` …) become unreadable past a dozen rows and
lose the subtotals that make the shape legible.

**Two dimensions per axis, one for series.** Two covers every report the owner named plus
`Tag > Category` and `Category > Tag`, and caps the worst-case tree — each dimension may *itself* be
a hierarchy — at a depth that can still be read. A chart legend with nested groups is illegible, so
series takes exactly one.

**Measures are laid out along the column axis**, innermost (§5.4).

---

## 4. The dimension catalogue

| Dimension | Hierarchical | Grain | Notes |
|-----------|--------------|-------|-------|
| **Category** | yes (accounts tree, `income`/`expense`) | posting | per-currency at the leaf (data-model §6.5) |
| **Account** | yes (accounts tree, `asset`/`liability`/`equity`) | posting | |
| **Tag** | yes, **not** leaves-only | posting | overlapping lenses (§7.2); needs the unspecified row (§9.3) |
| **Payee** | no | transaction | |
| **Person** | no | posting | **debt leaves only** — see the limitation below |
| **Currency** | no | posting | `account.currency_code` |
| **Account type** | no | posting | the five `account.type` values |
| **Date** | yes (the ladder, §8.2) | transaction | `transaction.date`, the only date the model carries |

Filters only, never dimensions: `lifecycle`, `reconciliation`, note text. They partition nothing the
owner wants to read down a page, and as dimensions they would invite a "reconciled vs unreconciled"
grid that is a reconciliation tool, not a report.

### 4.1 The limitation: there is no "expenses by person"

`beneficiary_id` was deliberately dropped from `posting` (data-model §7) — "I paid €10 for Max" is a
posting to the `Max-EUR` **asset** account, not an attribute of the `Food` posting. So the **Person**
dimension can group *what people owe and how that moved*, and nothing else. "Which of my expenses
were for Max" is **not expressible**, and the engine must not pretend otherwise; the only way to
carry it is a tag convention. This is a consequence of the debts model, not a gap in reporting, and
it is recorded here so it is not rediscovered as a bug.

---

## 5. Measures

### 5.1 Two measures, two valuation rules, never unified

data-model §6.1 defines two valuation rules and forbids merging them. Reporting exposes exactly one
measure per rule:

- **Turnover** (a *flow*) — the signed sum of postings whose transaction date falls inside the
  period, valued **posting-by-posting at each posting's own date's rate**. January's CHF groceries
  stay valued at January's rate forever.
- **Closing balance** (a *stock*) — the cumulative position at the end of the period, valued at
  **that date's rate** (mark-to-market). The difference between the two rules *is* unrealized FX
  (data-model §6.2); summing posting base-amounts into a balance would give cost basis, which is
  wrong for net worth.

### 5.2 The aggregates are fixed, not chosen

**Turnover** aggregates with `sum` across both axes — over time and over accounts. That is the only
aggregate offered over it.

**Closing balance** aggregates with `sum` **across accounts** (data-model §5: a parent's balance *is*
the sum of its descendants, so "how much do I hold under Savings" is well-defined) and with `last`
**across time**. It is never summed along the time axis: last month's closing balance plus this
month's is not a quantity, and a cell or total that would require it renders `—` (§7).

No `min`, `max`, `avg`, or `first`. An earlier draft offered them over a sampled daily balance series
and they were cut: every one of them needs an order-of-operations rule that produces a defensible
number and an indefensible one (take the minimum of `Cash-EUR` and `Cash-CHF` per day and sum, and
you get a figure true of no moment in time; sum first and you get another), and neither the owner nor
Money ever asked the question they answer.

### 5.3 Legs

A turnover measure counts one of: **debits only**, **credits only**, **net** (debit − credit).

This is **independent of account type**, which is the whole payoff of data-model §4's sign
convention. Spending is a **credit** on a debit card (asset) and on a credit card (liability) alike:

```
Groceries €30 on the credit card        Groceries €30 on the debit card
  Food            +30  (debit)            Food            +30  (debit)
  CreditCard-EUR  −30  (credit)           BankAaa-EUR     −30  (credit)
Paying the card off €100                Salary €2000 in
  CreditCard-EUR +100  (debit)            BankAaa-EUR   +2000  (debit)
  BankAaa-EUR    −100  (credit)           Salary        −2000  (credit)
```

So `credits only` is spending on both, and "debit card vs credit card usage" is **one measure across
two rows** — no per-row configuration, which an earlier draft feared would be necessary. On the card,
debits are repayments; on the bank account, debits are money coming in.

**Sign presentation is not a report setting.** data-model §4.1's display rule already negates
credit-natural accounts (`income`, `liability`, `equity`) for display, which is why the matrix's
income block reads positive and a credit card's spending total reads positive. A per-report sign
override was rejected: it would let the same account render positive in one saved Report and negative
in another, which is exactly the second meaning the sign convention exists to prevent. A renderer
may flip an axis for presentation; a measure may not.

### 5.4 Presentation currency is part of the measure

Four measures, not two settings:

| Measure | Valuation |
|---------|-----------|
| Turnover, base currency | posting-by-posting at posting-date rates, summed in base |
| Turnover, account currency | native amounts, no rate lookup at all |
| Closing balance, base currency | native balance × rate at the period end |
| Closing balance, account currency | native balance, no rate lookup at all |

Because measures sit on the column axis (§3), "show me both" is just two columns — no report-wide
currency toggle, and a base column next to a native one is unambiguous thanks to the existing
formatting convention (base rendered bare, non-base carrying symbol/ISO).

**An account-currency cell renders a number only when every account contributing to it shares one
currency; otherwise `—`.** This has a useful consequence for categories: `Food` in account currency
is `—` because it spans `Food-EUR` and `Food-CHF`, but expanding `Food` gives per-currency leaves
that each show a real native figure — the currency structure becomes visible instead of being
silently averaged away.

### 5.5 Count

`count` of postings and `count` of distinct transactions are offered as measures alongside the four
above ("how many ShopAaa orders per month"). They aggregate with `sum` across both axes and need no
currency variant.

---

## 6. Scope and filters

### 6.1 Scope exists because every transaction sums to zero

Summing *all* postings over January returns **0.00**, by construction (data-model §4). So a flow
report cannot be defined by its dimensions alone — it needs an explicit **scope**: the account types
and/or account subtrees whose postings the measure counts. The matrix's scope is implicit in its rows
(categories ⇒ `income` + `expense`); a generic engine has to state it.

### 6.2 Filters come in two visibly different kinds

"What did I spend **from BankAaa**, by category" cannot be one filter. The transaction has a BankAaa
leg *and* a Food leg; if the filter and the measure both mean "postings on BankAaa", the category
breakdown is **empty**. What is wanted is: find transactions touching BankAaa, then sum their expense
legs — the filter names one leg, the measure sums a different one.

So a filter declares which it is, in words, in the UI:

- **"transactions touching …"** — transaction-level: the transaction qualifies if *any* of its
  postings match, and the measure then sums whatever the scope selects.
- **"amounts booked to …"** — posting-level: only matching postings are counted.

Defaults: Account, Payee, Person → transaction-level; Tag → transaction-level (for a category
breakdown of a trip you want the whole transaction's shape, because the fuel line carries the tag);
Category, Currency, Account type → posting-level. Every one of them is switchable, because both
readings are legitimate and they give different numbers. Hiding the distinction behind a fixed
per-field rule was rejected: the wrong reading is silent, and it is silently wrong in the direction
of a plausible-looking answer.

### 6.3 Operators

| Field | Operators |
|-------|-----------|
| Category, Account, Tag | `is one of` (multi-select) |
| Payee | `is one of`, `matches` (regular expression, **case-insensitive**) |
| Person, Currency, Account type, lifecycle, reconciliation | `is one of` |
| note text | `contains` |

**`is one of` on a hierarchy node includes its whole subtree.** There is no separate `is under`
operator: selecting `Food` means Food and everything beneath it, and selecting only
`Food:Restaurants` is how you ask for just that. For tags this also includes postings carrying the
selected tag **directly** (data-model §10.3), which is what the unspecified row (§9.3) surfaces.

Filters combine with **AND** across dimensions; the multi-select inside one filter is the OR. Cross-
dimension OR ("Food *or* tagged Prague") is not offered — it is a query-builder rabbit hole for a
question answerable with two Reports.

Payee regex is case-insensitive because the same merchant gets typed in different cases over years;
case-sensitivity here is a trap, not a feature.

### 6.4 What is in scope by default

| Axis | Default | Toggle |
|------|---------|--------|
| **Closed accounts** | **included** — their history is real, and a zero balance row is suppressed anyway (§7.3) | yes |
| **Soft-deleted** accounts, transactions, postings | **excluded, permanently** | **no** |
| **`pending_review` transactions** | **excluded** — recurring pre-registrations and unreviewed captures are not yet facts, and including them inflates the current period | yes, and the Report is **marked** while it is on |
| **Voided transactions** | excluded (`deleted_at`, same as soft-deleted) | no |

The resolved scope is printed as **one muted line in the Report header**, not buried in a settings
panel. "Why doesn't this match the register" is the most likely question this feature will generate,
and the answer is almost always one of these four rows.

---

## 7. Legality: what the engine refuses to print

### 7.1 Totals are per-axis, and switchable

The Report carries **totals on rows** and **totals on columns** as independent settings. They apply
to the table renderer only; charts have no totals.

### 7.2 `—` is not the same as empty

A cell or total renders `—` when the aggregate would be **arithmetically meaningless**:

| Situation | Why |
|-----------|-----|
| Closing balance summed along the **time** axis | last month's balance + this month's is not a quantity (§5.2) |
| An **account-currency** measure spanning two currencies | adding CHF to EUR (§5.4) |
| A **grand total across tags** | tags are overlapping lenses — a posting tagged `Car:Passat` *and* `Trip:Prague` is in both rows, so the total double-counts it (data-model §10.4) |

This is a computed refusal, not a warning: the number is **not rendered**. A number on screen gets
believed, and the discipline of not printing one is cheaper than the discipline of remembering
data-model §10.4 in eighteen months. Per-tag figures remain valid; only the total across them is not.

### 7.3 Empty, zero, and suppression

- **No postings** → the cell is **blank**.
- **Postings summing to zero** → `0,00`.

The distinction is real and costs nothing: it answers "did I actually spend nothing on Fuel in March,
or did the refund land?".

**Rows whose every cell is blank are suppressed by default** (toggleable). A full category tree has
dozens of leaves and most are empty in any month; showing the whole taxonomy every time buries the
signal.

### 7.4 Renderer legality

A **pie refuses a measure that can go negative** rather than drawing a nonsense wedge — a pie of net
turnover across categories where one is negative is not renderable, and the honest response is to
decline. The engine reports the refusal on the Report, next to the renderer picker.

---

## 8. The date range and the time dimension

### 8.1 Two endpoints, one grammar

The range is a **start** and an **end**, each independently either a literal date or an expression of
**unit × offset × edge** — unit ∈ {day, week, month, quarter, year}, offset ∈ ℤ (0 = current, −1 =
previous), edge ∈ {start, end}:

| You want | Start | End |
|----------|-------|-----|
| Year to date | year, 0, start | day, 0, start (*today*) |
| Last 12 months, incl. current | month, −11, start | day, 0, start |
| Previous month | month, −1, start | month, −1, end |
| Previous year | year, −1, start | year, −1, end |
| Current month only | month, 0, start | day, 0, start |

Named shortcuts ("Year to date", "Previous month", "Last 12 months") remain in the UI as one-click
buttons that **fill the two endpoints**, so what they resolved to is always visible and either side
can be nudged afterwards. This replaces both a range-preset list *and* an earlier
"include the current partial period" toggle: whether the current month is in range is now simply what
the end endpoint says, and a saved Report or a Frame on the main page stays fresh because the
endpoints are expressions, not dates.

**An end endpoint may resolve into the future** (`month, 0, end` on the 12th), and the report then
includes future-dated transactions. `pending_review` is already out by default (§6.4), so
pre-registered recurring entries do not leak in; a *confirmed* future-dated transaction is a fact the
owner entered deliberately. Clamping to today was rejected because the report would then silently
disagree with the range printed in its own header.

### 8.2 The ladder, and partial buckets

The Date dimension carries a **granularity ladder**, chosen per Report: `year → month → day` or
`year → week → day`. Buckets come from the ladder; the range only bounds them. **Week starts Monday**
(ISO, matching Postgres `date_trunc('week')` and European convention). **Fiscal years are calendar
years** — no offset.

A first or last bucket the range cuts short is **labelled as partial** (`Sep (to 12th)`). An
unlabelled short bar next to twelve full ones is the single most common self-inflicted wound in this
kind of report, and the label is why no toggle is needed to avoid it.

A **closing balance** in a bucket that extends past today is the balance **as of today**, not as of
the bucket's end — otherwise the final point of a net-worth line is a balance that has not happened.

---

## 9. Hierarchies

### 9.1 Expansion

Every hierarchical dimension (Category, Account, Tag) and the Date ladder renders as an **expandable
tree on the row axis**, expanded in place via htmx fragment swaps. **Column-axis expansion is not in
v1** — expanding a column reshapes the grid rather than adding a line.

Expansion state is **remembered against the saved Report** (not per browser, not in the URL): it is a
property of "my Food matrix", not of this session. It is deliberately the *one* piece of view state
that persists; sort order and the current range live in the URL. An unsaved Report falls back to its
initial setting each time, which is fine because it is throwaway.

### 9.2 The initial state is `auto`

Three states — **`auto`** (the default), `collapsed`, `expanded`. `auto` means: if the filter on that
dimension selects **exactly one** hierarchy node, start **expanded** (you asked about one thing; show
its parts); if it selects two or more, or none, start **collapsed**. Once expand/collapse is used by
hand on a saved Report, the remembered state (§9.1) takes over from `auto` for that Report.

A **parent row** is either a **subtotal** or a **group header only**, per Report. With `Tag is one of
{Trips}`, `rows = [Tag, Category]`, `auto` expansion and group-header parents, `Trips` renders as a
bare heading with one row per trip beneath it — the owner's original example.

### 9.3 The unspecified row — tags only

Tags are **not** leaves-only (data-model §10.3): a posting may carry `Trips` directly, with no
sub-tag. Expanding `Trips` therefore needs a row for those postings, rendered as a synthetic first
child labelled **`(unspecified)`**. It sits exactly where a real catch-all child would, needs no
repetition of the parent's name, and reads the same in every tree.

**Accounts and categories never have one**, because leaves-only posting (data-model §5) forces a real
`:General` child instead. A reader looking for the unspecified row under accounts will not find it;
that is correct.

---

## 10. Renderers

**Server-rendered inline SVG for every chart — no charting library, no fourth JS leaf.** The QR code
(tech-stack §4.5) already established the pattern: build the `<svg>` in Java, emit it in the page.
Pie and bar are trivial; a line chart with a trend line is the one FR-ANA-09 already required. The
only loss is interactivity, and FR-UX-03 forbids hover-to-reveal-the-number regardless.

| Renderer | rows | columns | series |
|----------|------|---------|--------|
| **Table** | nested groups | nested groups | unused |
| **Line** | small multiples | x-axis | the lines |
| **Bar** | small multiples | x-axis | the bar groups |
| **Pie** | small multiples | the slices | unused |

**Chart and table swap inside the same Frame.** Every chart has its grid available behind a toggle in
the same UI element — one deterministic click, not a hover, and not a second copy of the numbers
scrolling below the chart. The toggle's state is saved with the Report, like expansion state. This is
how FR-UX-03's numbers-first rule is met: the figures are never *unavailable*, and they are never
*only* obtainable by pointing at a pixel.

The **line renderer** carries FR-ANA-09's trend line as an option.

---

## 11. Layouts

The reporting page holds: **New report**, the **list of saved Reports** as links, and the **Layout**
of Frames rendering Reports inline.

A Layout is configured by choosing its **rows × columns**, which defines the Frames; each Frame then
gets a **dropdown** of Reports and Presets, and the whole thing is saved with one **Save layout**.
There is **no cap** on Frames and **no drag-and-drop** — a configurable grid plus dropdowns is the
same implementation effort as a fixed 2×2 and needs no JS, and an integer-position-per-Report scheme
(the alternative) has to resolve collisions that this one cannot have.

**The main page is a 1×1 Layout**, defaulting to the **net worth over time** Preset. Its Balances
panel (`CONTEXT.md` §Landing page) answers the composition half of "how much money do I have"; the
Frame answers the change-over-time half.

> **Why not Money's default.** Money's out-of-the-box main-page report was a pie of the biggest
> categories, which told the owner that rent was the biggest expense — something he already knew.
> The main page's job is to show what **changed**, not what things are **made of**: composition is
> stable and gets memorised, change carries information. This is the standard the default Preset is
> chosen against.

---

## 12. Drill-down

Every cell corresponds to a concrete posting set (FR-ANA-10). Clicking one opens a **transaction list
owned by the reporting feature**, visually identical to the register and reusing its row fragment.

It is **not** the register pre-filtered. The register's filter vocabulary is narrower than a Report's
— it cannot express "transactions touching BankAaa whose Food leg falls in March" — so handing the
cell to it would **silently drop constraints**, and a drill-down list you cannot trust is worse than
none.

From that list, editing a row **hands off to the register** with the transaction's
**`asset`/`liability` leg** pre-selected as the account and its date in range. A transfer has two such
legs: the **credited** one (the money's source) is chosen. A transaction with no such leg (a
category-to-category correction) opens the register unfiltered at that date.

---

## 13. Export

Every Report exports its grid as **CSV**: the **raw** grid — fully expanded regardless of expansion
state, ISO dates, plain decimal points, one row per leaf, base and native columns as separate
columns. A CSV goes into a spreadsheet where German display formatting (`1.234,56`) fights the locale
and a collapsed hierarchy would lose data. The rendered grid is what the screen is for.

PDF export (FR-ANA-06's parenthetical) is **not** in scope.

---

## 14. Persistence, URLs and the API boundary

- **A Report is a document, not a relation.** The `report` table stores the spec as **`jsonb`**, with
  a few **promoted columns** for what is actually queried on — name, renderer, and the Layout/Frame
  assignment. No normalised `report_dimension` / `report_filter` / `report_measure` tables: nothing
  ever asks "which reports filter on Food", and every v2 feature would otherwise be a migration.
- **Ad-hoc Reports carry the whole spec in the query string** — bookmarkable, shareable, and
  matching how the owner will actually work (fiddle with the URL, get something good, *then* name
  it). Saving mints a row and a short `/reports/{id}`. Presets live at
  `/reports/preset/{slug}` and are code-defined.
- **The engine's public API is `spec in → grid out`.** That is what the UI wants anyway, and it makes
  the eventual MCP read tool (FR-MCP) a thin wrapper rather than a refactor — the same
  "one implementation, two callers" shape CLAUDE.md §3 established for `operations`. **Nothing is
  exposed over MCP in v1.**
- **The engine lives in the `analytics` module**, whose only current inhabitant is `TrackingStats`.
  Renderers and controllers live there too; `web` keeps only the shell.
- **The filter component is reused.** The register's multi-select-with-group-toggles
  (`filter-groups.js`, transaction-register-ui/22, one of the three sanctioned JS leaves) is
  generalised over the three hierarchies rather than duplicated — and explicitly **not** a fourth
  leaf. The cost is that a change to it can now break two screens, so it earns acceptance coverage
  on both.

---

## 15. Performance: no materialization in v1

data-model §9 says compute on the fly and add a cache only when *measured* slow; CLAUDE.md requires
materializing running balances to be an **explicit decision**. The decision is: **no materialization,
no pre-emptive index.** The register already renders 1,500+ transactions across 20+ accounts on the
Pi with no difficulty, the Date ladder defaults to **month** granularity (≈60 points for five years,
not 1,800), and day granularity is reachable only by expanding a short range.

If it *is* slow, the honest fix is a materialized per-account monthly-balance rollup, and that gets
its own decision **with a measured number attached** — not a hedge built in now.

---

## 16. The Presets shipped, and why these

They are the acceptance set: the reports the owner actually opened in Money. If the engine can
express all four, it is expressive enough.

| Preset | rows | columns | series | Measure | Renderer |
|--------|------|---------|--------|---------|----------|
| **Net worth over time** | — | Date (month) | — | closing balance, base; scope `asset`+`liability` | line + trend |
| **Category × month matrix** (FR-ANA-07) | Category | Date (month) | — | turnover, base, net | table |
| **This month vs last** | — | Category | Date (month) | turnover, base, net | bar |
| **Balance sheet** | Account | — | — | closing balance, base + native | table |

Notes: the matrix's income-block-above-expense-block reading comes from Category ordering plus
data-model §4.1's display rule, not from a bespoke layout. **Net worth** = `asset` + `liability`,
excluding `equity` (opening balances), `income` and `expense`. **Per-person debt leaves are `asset`
accounts, so money owed to you counts toward net worth** — correct in that it is yours, just not in
your pocket, and the alternative would make settling up with someone *increase* your net worth out of
nowhere. The owner has reserved the right to revisit this once he has seen it (§17).

The **balance sheet** Preset does **not** replace the Accounts page: that page is where accounts are
*edited*, the Preset is where the balance sheet is *read*. Merging them puts a date picker on a
management screen.

---

## 17. Open questions

| # | Question | Status |
|---|----------|--------|
| Q-REP-1 | Do per-person debt leaves belong in net worth and the balance-sheet Preset? | Defaulting to **yes** (§16); owner to revisit after seeing it rendered |
| Q-REP-2 | Column-axis expansion, per-node expansion defaults, per-node subtotal overrides | Deferred past v1 (§9.1) — revisit from use |
| Q-REP-3 | `amount between` as a filter | Dropped from v1; it is a register-search need more than a reporting one |
| Q-REP-4 | The monthly narrative report (FR-RPT) | Stays in the backlog; **revisit with budgets**, since "fact against budget" is the half that makes it worth writing |

---

## Changelog

- **v0.1 (2026-09-12):** Initial design, from a full grilling pass. Establishes the report engine
  (dimensions, the four measures, legs, scope, the two filter kinds, the legality rules), the
  start/end anchor grammar, hierarchies with `auto` expansion and the `(unspecified)` row, four
  renderers as server-rendered SVG with the chart/table swap, Layouts with the main page as a 1×1,
  reporting-owned drill-down, CSV export, `jsonb` persistence, and no materialization in v1.
  **Supersedes** the two-hand-built-reports plan; FR-ANA-07/08/09 rewritten in `requirements.md`,
  rationale in ADR-0001. Q-REP-1–4 left open.
