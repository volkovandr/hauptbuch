# Hauptbuch — Core Data Model

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.5
**Date:** 2026-07-11
**Owner:** volkovandr
**Companion to:** `requirements.md` (v0.4),
`tech-stack.md` (v0.1)

> This document records the **core data model** — accounts, transactions, postings, currency,
> exchange rates, FX handling, per-person debts, payees, and tags — together with the conventions
> and integrity rules that make it correct. As in the tech-stack doc, decisions are recorded **with
> their reasoning so the _why_ survives long after the _what_ is code.**
>
> Scope note: this is the *core* (the double-entry engine, money valuation, and the tag dimension).
> Recurring templates, subscriptions, budgets, attachments, and holdings are deliberately **not
> modeled here yet** — see §12.

**Changelog**
- **v0.6 (2026-07-12):** Currency leaves get a real marker (§6.5): `account.currency_leaf` (boolean)
  replaces the old `"<Parent> <CODE>"` name-matching heuristic, which two services duplicated and
  which a user-named category could collide with. Currency leaves are now named after their bare
  currency code and are structurally invisible (pickers, categories screen); subdividing a category
  that already has currency leaves re-parents them onto the new `Uncategorized` catch-all rather than
  leaving them stranded as siblings of the new real child.
- **v0.5 (2026-07-11):** **FX gain/loss auto-booking retired** (§6.3). The engine no longer invents a
  residual leg at a conversion; a cross-currency transaction must balance in base **from the entered
  legs**, and the rare over-determined case is refused by the sum-to-zero invariant for the user to
  resolve by hand. With no code path resolving it by name, `FX gain/loss` is **no longer a seeded
  system leaf** — it is a plain category the user creates on demand and posts to by hand. Refined the
  category-currency rule (§6.5): the leaf currency **defaults** to the paying account's currency but
  is **selectable** in entry — overriding it declares a cross-currency purchase (an EUR card buying a
  CHF-priced item). No change to `base_amount` freezing or mark-to-market valuation.
- **v0.4 (2026-06-26):** Ratified the root **`settings`** entity (§3.8) — single-row table holding
  the **write-once `base_currency`** (required before any transaction, immutable thereafter) and the
  `display_name`. Base currency thus lives **in the database**, not config (it is a property of the
  book: travels with `pg_dump`, per-profile), closing the gap the implementation plan (§1.3) flagged.
  Reworded "Categories ARE accounts" → **"categories are backed by accounts"** (a category is a set
  of same-named per-currency `income`/`expense` accounts; the `categories` module owns the logic that
  keeps them consistent).
- **v0.3 (2026-06-22):** Naming convention adopted (§3.0): every entity has a surrogate PK named
  `<entity>_id`, so FK columns reuse the target's PK name. Two exceptions: externally-defined
  entities keep their natural key (`currency.currency_code`, ISO 4217); self-references use a role
  name (`parent_id`). Junction tables get their own surrogate PK with the natural pair as `unique`.
  Added **tags** (§10): classical many-to-many on the *posting*, `tag` entity with a self-referential
  `parent_id` (hierarchy present, rollup behaviour live), and the distinct-posting rollup rule.
- **v0.2 (2026-06-22):** Multi-currency categories — expense/income leaves are per-currency, the
  same way per-person debt accounts are (§6.5). The expense leaf's currency is *determined* by the
  payment currency, not chosen; a CHF purchase from a CHF card stays a clean single-currency
  transaction. Stated the general rule that cross-currency aggregation is always done in base, and
  that the two valuation rules partition by account type (stock vs flow).
- **v0.1 (2026-06-21):** Initial core model. Single-sided signed postings; categories-as-accounts;
  sign convention (+ = debit); leaves-only posting; per-person signed debt accounts via an owner
  link; nullable frozen `base_amount`; two distinct on-the-fly valuation rules (flows vs balances);
  FX policy #3 (holding-period FX lives in net worth only, booked solely at real conversion
  events); conditional sum-to-zero invariant.

---

## 1. Modeling principles (why the model looks like this)

These follow directly from the tech-stack doc's overriding constraint — *the codebase must stay
navigable and correct without AI-agent assistance* — applied to data.

1. **One representation, reused.** Categories, real accounts, and per-person debts are all
   **accounts**; income, expense, splits, transfers, and debts are all **postings**. Fewer
   concepts ⇒ one balance engine, one report path, less to hold in your head.
2. **Double-entry with a trivially checkable invariant.** Every transaction's postings sum to
   zero. This is the cheapest, highest-value correctness test in the system; the sign convention
   exists to make it true *by construction*, with no per-account-type reasoning.
3. **Derive, don't materialize — until measured slow.** No stored running balances, no universally
   materialized base-currency amounts. Balances and conversions are computed on read from postings
   + rates. A value is stored **only** when it is a frozen historical *fact* that must never be
   recomputed (see `base_amount`, §6).
4. **Native SQL friendliness.** The shapes here (windowed running sums, `generate_series` sampling,
   sparse-rate carry-forward joins) are written as literal PostgreSQL and tested against real
   Postgres (Testcontainers) — consistent with the JdbcClient + records choice.

---

## 2. Entity overview

```
currency ──< account >── account_owner ──> person
                │
                │ (account_id, leaf only)
                │
transaction ──< posting >── posting_tag ──> tag ──┐
     │                                            │ (parent_id self-ref)
     └──> payee                                   └──┘

exchange_rate (currency_code, date) — lookup cache, revalues held balances
```

- **account** — real accounts, **and** categories (income/expense), **and** per-person debts.
  Hierarchical. One native currency each.
- **transaction** — an economic event. Has a date and a payee; **carries no amount**.
- **posting** — one signed leg hitting exactly one (leaf) account. The legs of a transaction sum
  to zero.
- **payee** — external counterparty/merchant (metadata, not an account).
- **person** — a contact; *owns* their per-currency debt accounts via **account_owner**.
- **tag** — an orthogonal, overlapping label dimension on postings, hierarchical via `parent_id`.
- **currency / exchange_rate** — ISO currencies and a sparse, carry-forward rate cache.
- **settings** — single-row root entity holding the write-once base currency and the display name
  (§3.8).

---

## 3. Tables

> Types are PostgreSQL. These are the **canonical model**, not the final Flyway migrations —
> indexes, exact column nullability, and id strategy are refined when the migrations are written.
> `id` strategy (identity vs UUID) is **open** (T-DM-1); identity shown as a sensible default.
> Monetary columns are `numeric` — never floating point (per tech-stack §2.4); the JSR-354 /
> Joda-Money type lives at the application layer.

### 3.0 Naming convention (governs every table)

- **Every entity has a surrogate primary key named `<entity>_id`** (`account.account_id`,
  `posting.posting_id`, `tag.tag_id`, …). A FK therefore **reuses the target's PK name**:
  `posting.account_id` references `account.account_id`. The column name alone identifies its target,
  every column in a multi-table result set is unambiguous without aliasing, and "what references
  account?" is a grep for `account_id`. (We still write explicit `ON` joins; we do **not** use
  `USING`, which the convention would technically permit.)
- **Exception 1 — externally-defined entities keep their natural key.** An entity whose key we did
  not invent but inherited from the outside world keeps that key instead of a surrogate. `currency`
  uses the ISO 4217 code (`currency.currency_code`, text); a future `country` would use its ISO-3166
  code, etc. Rationale: the key is immutable, globally meaningful, and human-readable —
  `account.currency_code = 'CHF'` is self-explanatory where an opaque `currency_id = 2` would force
  a join to interpret, and the natural key never changes so the usual surrogate argument doesn't
  apply. FKs still reuse the name (`account.currency_code`).
- **Exception 2 — self-references use a role name.** A FK from a table to itself cannot reuse the
  PK name (that *is* the row's own key), so it takes the obvious role name: `account.parent_id`
  references `account.account_id`; `tag.parent_id` references `tag.tag_id`.
- **Junction tables follow the rule too** — own surrogate `<junction>_id` PK, with the natural
  column pair as a `unique` constraint (`account_owner`, `posting_tag`).

### 3.1 `currency`  *(externally-defined — natural key)*

```sql
create table currency (
  currency_code text primary key,      -- ISO 4217, e.g. 'EUR', 'CHF' (inherited, not surrogate)
  minor_units   smallint not null,     -- 2 for EUR, 0 for JPY — drives rounding
  symbol        text,
  name          text not null
);
```

Seed only the currencies actually used, via Flyway — not all ~180 ISO codes. The stage-3 seed is
**EUR, CHF, USD, GBP, JPY, CZK, PLN** (the home/travel set, including the Prague-trip examples; JPY
is the zero-minor-units case the rounding path must handle). **Base currency is a write-once value in
the single-row `settings` entity** (§3.8), not a per-currency row flag (one base per book).

### 3.2 `account`

```sql
create table account (
  account_id    bigint generated always as identity primary key,
  name          text not null,
  type          text not null
                check (type in ('asset','liability','income','expense','equity')),
  parent_id     bigint references account(account_id),     -- self-ref; NULL = top level
  currency_code text not null references currency(currency_code),
  opened_at     date,
  closed_at     date,
  deleted_at    timestamptz                                 -- soft delete
);
```

- **Categories are backed by accounts.** Spending €5 on coffee is `credit Cash −5, debit Food +5`
  — `Food` is an `expense` account. There is **no separate category table**; a category is a set of
  same-named `income`/`expense` accounts, one per currency (`Food-EUR`, `Food-CHF` under a common
  `Food`, §6.5). The `type` column lets the UI still present Accounts and Categories as separate
  lists (Money's *look*, with double-entry's *integrity*). The category-specific logic that keeps
  these backing accounts consistent (subdivision, per-currency-leaf routing) lives in the
  `categories` module, distinct from logic that applies to all accounts.
- **`type` is five values.** `asset | liability | income | expense | equity`. `equity` anchors
  opening balances and net worth (an opening balance posts against an *Opening Balances* equity
  account). There is intentionally **no `receivable` type** — per-person debts are `asset`
  accounts allowed to go negative (§7).
- **Currency is per account**, and postings inherit it — so `posting` stores no currency column
  (it's `account.currency_code`). This applies to **categories too**: an expense leaf has one
  currency, so `Food` paid in CHF and `Food` paid in EUR are two leaves (`Food-CHF`, `Food-EUR`)
  under a common parent — see §6.5.
- **Hierarchy: leaves-only posting** (§5). Not enforced as a DB constraint — verified by test.

### 3.3 `person` and `account_owner`

```sql
create table person (
  person_id  bigint generated always as identity primary key,
  name       text not null,
  deleted_at timestamptz
);

create table account_owner (
  account_owner_id bigint generated always as identity primary key,
  account_id       bigint not null references account(account_id),
  person_id        bigint not null references person(person_id),
  unique (account_id)                       -- one owner per account
);
```

`person` survives even though **`beneficiary_id` was dropped** from postings (§7): "I paid for
Max" is a *posting to Max's account*, and the account is linked back to the person here.
`account_owner` keeps the `account` table clean (no person columns on it) and makes "is this a
per-person debt account?" answerable by the presence of an owner row. A person has **many**
accounts — one per currency (`Max-EUR`, `Max-CHF` …), each a separate leaf, each its own owner row.
The `unique (account_id)` carries the "one owner per account" integrity that the old natural PK did;
the surrogate `account_owner_id` keeps the table consistent with the naming convention (§3.0).

### 3.4 `payee`

```sql
create table payee (
  payee_id   bigint generated always as identity primary key,
  name       text not null,
  deleted_at timestamptz
);
```

External counterparty (Rewe, Shell, the landlord). **Not an account** — it's metadata on the
transaction driving "spend by payee" and a stable id for the rules engine and learned
merchant→category mappings. Its own table rather than a string for exactly that stability.

### 3.5 `transaction`

```sql
create table transaction (
  transaction_id bigint generated always as identity primary key,
  date           date not null,                       -- booking date; NO amount column
  payee_id       bigint references payee(payee_id),   -- nullable (transfers have none)
  note           text,
  lifecycle      text not null default 'confirmed'
                 check (lifecycle in ('pending_review','confirmed')),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz
);
```

- **No `amount`.** The amount lives in the postings; a transaction-level amount would be a second
  source of truth that drifts on splits. Total is `sum` of the relevant postings.
- **`lifecycle` and `deleted_at` are orthogonal axes** — two columns, never one merged enum.
  `lifecycle` = where it is in review (`pending_review` for recurring pre-registrations and
  review-pending captures → `confirmed`); `deleted_at` = whether it's live, and *when* it was
  removed (needed for reversible soft-delete). Folding "deleted" into `lifecycle` would destroy the
  prior state required to restore it.
- Soft-deleting a transaction soft-deletes its postings with it; integrity checks scope to
  `deleted_at is null`.

### 3.6 `posting`

```sql
create table posting (
  posting_id     bigint generated always as identity primary key,
  transaction_id bigint not null references transaction(transaction_id),
  account_id     bigint not null references account(account_id),   -- always a LEAF
  amount         numeric(19,4) not null,   -- signed; native currency of the account
  base_amount    numeric(19,4),            -- NULL = derive on the fly; non-null = frozen fact
  reconciliation text not null default 'unreconciled'
                 check (reconciliation in ('unreconciled','cleared','reconciled')),
  note           text
);
```

- **Single-sided, signed.** Each row hits exactly one account. `amount` sign convention: **`+` =
  debit, `−` = credit** (§4). A purchase is 2 rows, a three-way split is 4 rows, a transfer is 2
  rows — all the same shape.
- **No currency column** — it's `account.currency_code` (each account has exactly one currency).
- **`base_amount` is nullable on purpose** (§6): `NULL` means "compute from the feed on the fly";
  non-null means "this is a frozen base-currency value from a *real conversion event* and must
  never be recomputed."
- **`reconciliation` is per-posting**, not per-transaction — you reconcile one account against one
  statement at a time; a transfer's two legs clear on different statements on different dates.

### 3.7 `exchange_rate`

```sql
create table exchange_rate (
  exchange_rate_id bigint generated always as identity primary key,
  currency_code    text not null references currency(currency_code),  -- the foreign currency
  date             date not null,
  rate             numeric(19,8) not null,   -- units of BASE per 1 unit of currency_code
  source           text not null check (source in ('ecb','manual')),
  unique (currency_code, date)
);
```

- Surrogate `exchange_rate_id` per the convention; the meaningful `(currency_code, date)` pair is a
  `unique` constraint. (Nothing FKs to this table — it's a lookup/cache — so the surrogate is purely
  for uniformity; a composite natural PK would also have been defensible here.)
- **A lookup cache, not the source of booked conversions.** It is consulted to (a) propose a rate
  on entry and (b) **revalue held foreign balances** into base for net worth. It **never**
  retroactively rewrites a booked conversion — those are frozen on the posting (§6).
- **Stored against base**, daily, **sparse with carry-forward.** Lookup is "most recent rate on or
  before D":
  ```sql
  select rate from exchange_rate
  where currency_code = :c and date <= :d
  order by date desc limit 1;
  ```
  Monthly ECB rows and occasional manual rows coexist naturally. Manual entry (e.g. "use this rate
  from now on") inserts a `source='manual'` row valid from its date forward until superseded.
  Pivot CHF→USD through base (CHF→base→USD).

### 3.8 `settings`  *(single-row root entity)*

```sql
create table settings (
  settings_id   smallint primary key default 1 check (settings_id = 1),  -- single-row guard
  base_currency text references currency(currency_code),  -- write-once; NULL until first-run set
  display_name  text                                      -- the "Hello, %name%" greeting
  -- future global settings land here as typed columns (legibility > key/value bag)
);
```

- **One row, enforced.** The `settings_id = 1` check plus the `default 1` PK make this a single-row
  table — global book settings, not a key/value bag. Future settings are added as **typed columns**
  here (legibility over an EAV soup), the same stance the rest of the model takes.
- **`base_currency` is write-once.** It is **required before any transaction is recorded** (a frozen
  `base_amount` is denominated in it, and the whole FX interpretation hangs off it), and **immutable
  thereafter** — changing it would invalidate every frozen `base_amount` and every booked conversion.
  The engine **refuses to record a transaction while `base_currency` is `NULL`**. It is `NULL` only
  on a fresh book, until first-run setup sets it.
- **Write-once is enforced at the application layer** (the `settings` service refuses to overwrite a
  non-null `base_currency`); a constraint trigger is optional — the same stance as the sum-to-zero
  invariant (§8, T-DM-2).
- **`display_name`** backs the "Hello, %name%" greeting; freely editable. The base-currency UI is
  read-only once locked.

> **Why base currency moved out of config and into the DB.** It is a *property of the book*, not of
> the deployment: it must travel with a `pg_dump`, survive a restore, and be per-profile (each
> profile is its own database, ARCH-10/§5.14). A config-file value would drift from the data it
> governs. Born at the engine layer (the engine depends on it); its UI is the first-run settings
> screen.

---

## 4. The sign convention (load-bearing)

**`+ = debit`, `− = credit`. The postings of a transaction sum to zero. This holds regardless of
account type.**

This is *the* reason the integrity check is cheap: balanced double-entry **is** "total debits =
total credits," so a bare signed sum is zero by construction for any mix of account types — no
per-type sign-flipping before summing.

Why the tempting alternative ("`+` = increases the account's own balance") was rejected: it breaks
on income and liabilities. A salary of €100 would push both Checking *and* the salary account
"up" (+100 / +100 = +200 ≠ 0), because asset/expense accounts are debit-natural while
income/liability/equity are credit-natural. "Increase" points in opposite directions by type, so
that rule can't sum to zero without a type lookup.

**Worked example** — €30 supermarket trip, €20 your food, €10 fronted for Max:

```
Cash      −30   (credit; asset down)
Food      +20   (debit;  expense up — your share)
Max-EUR   +10   (debit;  Max owes you — asset up)
            0   ✓
```

### 4.1 Display rule (the necessary corollary)

Stored signs read differently by natural side:

- **Asset / expense** (debit-natural) → **positive** stored balances read naturally (Cash you
  hold is positive; €500 spent on Food is +500).
- **Income / liability / equity** (credit-natural) → **negative** stored balances; **negate for
  display.** A €3000 salary is stored −3000; the matrix's income block flips it to +3000.

The category×month matrix has an income block and an expense block carrying **opposite stored
signs** — note this once here rather than rediscovering it when income renders negative.

---

## 5. Hierarchy & leaves-only

**Postings may hit leaf accounts only. A parent's balance is, by definition, the sum of its
descendants.**

This gives every parent exactly one meaning (the rollup) and makes the matrix trivial: a parent
row is always `sum(children)`. Allowing direct postings on a parent would make its balance mean two
things and force every report to disambiguate.

- **Cost:** an explicit catch-all leaf instead of posting to the parent. Adding `Food:Restaurants`
  means `Food` gains a `Food:General` (or `:Other`) leaf for unclassified food. That's honest —
  "unclassified Food" is a real bucket, not the absence of one.
- **Not a DB constraint.** "Is this a leaf?" depends on whether any *other* row names it as parent,
  which is awkward in SQL and changes over time. Enforce in the application layer; verify with a
  test query (alongside sum-to-zero).
- **Subdivision is a §5.18 operation.** "Food was a leaf, now it has children" = create child +
  reassign existing postings to a designated child. It's a merge/reassign, already in scope.

> **Note — accounts vs tags hierarchy differ.** Accounts are leaves-only (a parent is a *pure*
> rollup, protecting the balance's single meaning). **Tags are not** (§10): a posting may carry a
> parent tag directly *and* sibling leaf tags, because tags have no sum-to-zero invariant to protect.
> Don't assume the two hierarchies behave the same.

---

## 6. Multi-currency & FX

This is the subtle part. Three separable concerns, kept separate on purpose.

### 6.1 Two valuation rules — deliberately different

**Flows** (income/expense over a period — the matrix, "Food in January") are valued
**posting-by-posting at each posting's own date's rate**, frozen as history:

```
flow_base(P) = Σ over postings p in P of
               coalesce(p.base_amount, p.amount * rate_as_of(txn.date, account.currency_code))
               -- rate_as_of returns 1 when account.currency_code = base
```

January's CHF groceries stay valued at January's rate forever (requirements FR-CUR-04).

**Balances / net worth** (a standing balance at a point in time — net worth, the consolidated
timeline) are valued **as the whole native balance × the rate as of the report date**
(mark-to-market through time):

```
native_balance(A, D) = Σ p.amount for postings of A where txn.date <= D
balance_base(A, D)   = native_balance(A, D) * rate_as_of(D, A.currency_code)
net_worth(D)         = Σ balance_base(A, D) over asset/liability (and later holding) accounts
```

These are **not** the same computation, and the difference is exactly the unrealized FX/price gain
(`market = cost_basis + unrealized_gain`). Summing posting base-amounts into a balance gives **cost
basis** (a CHF balance bought in January stays at January's rate even after the rate moves; a share
bought at €100 sits at €100 forever) — wrong for net worth. Valuing the **standing balance at D's
rate** walks the balance forward as rates move, so the timeline shows FX swings as they actually
happened. Both are still on-the-fly and both refresh when you edit a past rate. **Do not unify
them.**

> "Historical rate" here means *the rate as of the report date D* (not today's latest), applied to
> the standing balance — **not** each posting at its own acquisition-date rate.

### 6.2 FX policy — **#3: holding-period FX lives in net worth only**

Holding a foreign balance while the rate moves produces **no posting**. The appreciation/depreciation
appears **only** in net worth (via §6.1's balance rule). The P&L (matrix) is **FX-blind by design**.

Consequence, accepted: for any period with foreign holdings, `income − expenses ≠ net-worth
change`; the gap is unbooked FX. This is exactly Microsoft Money's behaviour, and it's correct for a
personal tool — FX appreciation on a holiday account isn't income you *earned*, it's an artifact of
which currency you're standing in. It belongs in net worth, not the spending report.

**Why this is consistent, not lossy** — the Switzerland round-trip (€1000 → CHF in Jan @0.91,
rates 0.90/0.95/0.98 across Jan/Feb/Mar, back out @0.97):

| Event | Net-worth effect (base EUR) |
|-------|-----------------------------|
| Jan transfer in (spread) | −10.99 |
| Jan→Feb revaluation | +54.95 |
| Feb→Mar revaluation | +32.96 |
| Mar transfer out (spread) | −10.99 |
| **Total** | **+65.93** = the real cash gain |

Nothing is missing; the gain enters as a string of revaluation rises plus two spread dips, and it
reconciles — with no FX income posting. This works **only because balances are
`native × rate-as-of-D`, never a sum of posting base-amounts.** A round-tripped CHF account ends at
`native = 0`, so its base value is `0 × anything = 0` — the would-be phantom credit (−65.93 in an
empty account) is never computed, so it never bites.

**Rejected alternatives:** #1 (remeasure on every rate change — IAS 21 for companies) sprays FX
postings across every foreign account on every rate update and pollutes the P&L with unrealized
noise. #2 (realize on disposal) needs cost-basis lot tracking (FIFO/average) the moment you do
partial conversions, round-trips, or third-currency hops — an accounting engine not worth building
or hand-auditing (legibility constraint).

### 6.3 `FX gain/loss` is a manual account, not an engine behaviour

Holding a foreign balance books no posting (§6.2); **neither does the engine invent one at a
conversion.** A cross-currency transaction must balance in base (`Σ base_amount = 0`) **from the legs
the user actually enters** — the engine never auto-inserts a residual leg. **A rate number changing
is not an event** and books nothing either.

Almost every real conversion balances by construction: you know one side in base (the EUR that
actually moved) and freeze the other leg's `base_amount` to match (§6.4), so there is no residual.
The base sum *only* fails to reach zero in a genuinely **over-determined** event — reality hands you
two independent base values that disagree (a statement stating the base value of *both* foreign legs;
a third-currency hop carrying a spread the bank kept). There the sum-to-zero invariant (§8.1)
**refuses the transaction**, and the user adds the balancing leg by hand — normally to `FX gain/loss`.

So `FX gain/loss` is just a category you **create on demand and post to manually**, exactly like any
other, when a statement hands you an explicit conversion gain/loss. Because no code path resolves it
by name (unlike `Opening Balances`, which the engine looks up), it is **not seeded and not
per-currency-provisioned** — it comes into being lazily on first use, the same way every category
leaf does (§6.5). It is a **single signed leaf** (sign tells gain vs loss), not a
receivable/payable-style pair — same reasoning as the per-person debt account.

**Why no auto-booking (decision, 2026-07-11).** Under policy #3 the honest conversion gain already
lives in net worth via mark-to-market (§6.2) — the Switzerland round-trip in §6.2 reconciles to the
real cash gain with *no* FX posting. An auto-booked residual would therefore either double-count that
gain or fire only in the rare over-determined case: machinery that is invisible when it matters and
wrong when it doesn't. Making it a manual leg keeps the engine legible (§0) — it enforces balance and
never silently conjures a posting; when money genuinely doesn't reconcile, *you* say why.

> **Caveat when entering a statement's "FX gain" line:** enter it only when it is a **separate**
> credit the bank actually posted — not a restatement of the spread already embedded in the two
> converted amounts, which is already in net worth. Otherwise you count it twice.

### 6.4 Cross-currency vs single-currency (and what `base_amount` is for)

**Single-currency transactions** (even foreign — CHF cash → CHF food): all legs share one currency,
so **native amounts sum to zero**. No stored base needed; `base_amount` stays `NULL` and is derived
on the fly for reporting. Rate edits propagate. **This is 95%+ of all transactions.**

**Cross-currency transactions** (inter-currency transfers, cross-currency settle-up, statement
conversions): native legs **do not** sum to zero, so the only balance check is in base, and the base
values must be the **real amounts** from the actual event — which encode the rate that transaction
*actually happened at*. These are stored, **frozen**, on `posting.base_amount`, and the feed never
touches them.

```
Transfer 100 CHF that arrived as €95:
  posting  CHF account  amount −100   base_amount −95.00   (frozen)
  posting  EUR account  amount  +95   base_amount +95.00   (= amount; base currency)
  Σ base = 0 ✓   implied rate 0.95 = a fact of this event, not a feed lookup
```

If the CHF leg were recomputed from the feed (say 0.92 that day → −92 vs +95) the transaction would
no longer balance. Hence **`base_amount` is the entire residue of materialization**: it doesn't
vanish, it *concentrates* onto the handful of postings that are genuinely two-currency, where it is
a frozen historical fact. `NULL` everywhere else.

> Two cleanly separated rate sources: **transaction rate** = what actually happened (frozen on
> cross-currency postings); **ECB feed** = how *held balances* are revalued in reports (`exchange_rate`,
> §3.7). The transfer never consults the feed; the revaluation never consults the transfer.

### 6.5 Multi-currency categories — expense/income leaves are per-currency

A consequence of "every account has exactly one currency" (§3.2), applied to categories. It is the
**same pattern as per-person debts** (§7): one leaf per (category, currency) under a common `Food`
parent — not one `Food` account holding mixed-currency postings.

**The leaf is marked, not named.** `account.currency_leaf` (boolean, default `false`) flags a leaf
as auto-provisioned by the currency-routing operation, as opposed to a real category a human named.
A currency leaf is simply named after its own currency code (`EUR`, `CHF`) — the flag, not the name,
is what marks it, so no naming convention can collide with a genuine user category. Marked leaves
are **structurally invisible**: excluded from every category picker and the categories screen, never
independently selectable, and carried along automatically wherever their parent goes (subdivided,
deleted) — renaming the parent needs no cascade, since the leaf's own name never referenced it.

**This is forced, not a preference.** Consider 10 CHF of food paid from a CHF card. The bank bills
10 CHF; **no conversion happens anywhere.** If `Food` were EUR-only, the debit would have to be in
EUR:

```
credit  CHF-card  −10 CHF
debit   Food(EUR)  +? EUR     ← a conversion that never occurred in reality
```

— which manufactures a cross-currency transaction (legs no longer sum to zero natively; a frozen
`base_amount` and an invented rate/FX event required) out of a purchase that was pure CHF end to
end. Wrong. So a CHF currency leaf under `Food` **must** exist precisely so the CHF purchase stays a
clean single-currency transaction (§6.4). Hence: **for a single-currency transaction the expense
leaf's currency equals the paying account's currency** — not a free choice, because every leg shares
one currency. In entry the category-currency selector therefore **defaults** to the paying account's
currency (the single-currency path, 95%+ of transactions, where currency is never touched);
**overriding it to another currency is precisely how the user declares a cross-currency purchase**
(EUR card, CHF price tag → `Food`'s CHF leaf, base frozen per §6.4). On a **transfer** both legs are
real accounts, so both currencies are fixed and nothing is chosen.

**Why the lifetime balance of `Food` is meaningless — two reasons, one deep.** The shallow one is
that `10 CHF + 10 EUR` is a nonsense native sum. The deeper one is that **the standing balance of
an expense account is the wrong question by construction**, currencies aside. The two valuation
rules (§6.1) in fact partition exactly by account type:

| Account type | You care about | Rule |
|--------------|----------------|------|
| asset / liability / equity | the **balance** (a stock) | stock rule — `native_balance × rate@D`, mark-to-market |
| income / expense | the **flow** in a period | flow rule — `Σ posting × rate@posting-date` |

`Food` is an expense account ⇒ inherently a flow account; its since-forever balance is something you
never ask for. **"How much on Food in January" is a flow**, and the flow rule converts *each posting
at its own date's rate*, so it absorbs however many currency leaves `Food` has: its EUR leaf's
January postings at ×1, its CHF leaf's at January's CHF rate, summed in EUR. The multiple-leaves
fact adds **zero** complexity to the January report — the flow rule already handles it.

**The general rule this instance illustrates:** *cross-currency aggregation is always done in base,
never as a native sum.* Net worth sums balances in base; per-person debt shows per-currency with a
base gloss (§7); a `Food` parent rolls its currency-children up **in base** via the flow rule.
Native sums are valid only within a single currency. Multi-currency categories are just one more
instance — the model stays uniform.

**Ergonomics (so the proliferation doesn't bite):**

- A leaf appears **only if you actually spend that currency** on it; most categories stay
  single-currency forever, and a currency leaf is invisible in every picker and the categories
  screen (it never competes with `Food` itself for attention there).
- Because the leaf currency is *determined*, the UI lets you pick `Food` **semantically** and
  auto-routes the posting to the right currency leaf — you never hand-pick it directly.
- The first time a EUR-only `Restaurants` leaf receives a CHF expense, it becomes a parent and its
  old postings move to a new CHF leaf — which **is** the subdivision domain operation already
  specced under leaves-only (§5). No new machinery.
- **A category that already has currency leaves is still a leaf, for subdivision purposes.** Giving
  `Food` (already split into currency leaves from ordinary spending) a genuine new child — say
  `Restaurants` — must not just insert it as a plain sibling of the hidden currency leaves: it
  subdivides exactly as the posted-leaf case above, spawning `Food`'s usual `Uncategorized` catch-all
  and **re-parenting the existing currency leaves onto it** (not reassigning their postings — the
  leaves keep their own identity and history, only their `parent_id` changes). `Food` ends up with
  two real children, `Restaurants` and `Uncategorized`; the currency leaves live one level deeper,
  still invisible, still holding their original postings.
- Minor: expense accounts are *mostly* debit-only, but **refunds credit them** (return the CHF food,
  get 10 CHF back = credit its leaf). The leaf stays single-currency; its period flow stays
  well-defined.

---

## 7. Per-person shared debts

Falls entirely out of the account model — no separate debt machinery (requirements FR-DEBT-09).

- **One signed account per (person, currency)** — `Max-EUR`, `Max-CHF` — `asset` type, owned via
  `account_owner`, allowed to go negative. **The sign of the balance is the direction:** positive =
  Max owes you (behaves as an asset); negative = you owe Max (behaves as a liability). You never
  decide "receivable or payable" at entry — the running balance answers it. This *is* FR-DEBT-05's
  "nets both directions automatically."
- **`beneficiary_id` was dropped.** "I paid €10 for Max" is just a posting to `Max-EUR`; who/how
  much/when come from the posting and its sibling postings (dinner at Rewe on the 14th). The owner
  accepted losing a *categorized* breakdown of what's been fronted ("seeing I paid for Max at a
  restaurant is enough").
- **Auto-provisioning is a code path, not schema.** First time a line is marked as Max's,
  a `debts` domain op ensures the person row and the needed per-currency leaf exist and links the leaf
  via `account_owner`. Entry rides the **transfer** path — a person leg is a transfer to/from that
  leaf — typed `for <person>` (`→ Person`, you funded) / `by <person>` (`Person →`, they funded) in
  the register (§3.5, §3.8 of the register doc).
- **No parent account; leaves grouped by `account_owner`.** The per-currency accounts are standalone
  `asset` **leaves** (not children of a `Max` parent), so nothing ever rolls up across currencies.
  Grouping "these leaves are Max's" is the `account_owner → person` link, never a naming convention;
  leaf names are cosmetic (`personal.<CUR>`, duplicates allowed) and every display resolves the
  person's name via that link. **Rename** updates `person.name` only — ids never move. **Duplicate
  person names are allowed**, disambiguated in pickers (as payees are).
- **Lifecycle.** A person is created explicitly or inline (first `for`/`by` reference), renamed, and
  **soft-deleted only when every leaf balance is zero** — a soft-deleted person is hidden from pickers
  but keeps all history and is **revived by confirmation** when the name is re-entered. A **non-zero**
  person is removed only by **merge**: reassigning their postings, per currency, onto another person
  (the `operations` reassignment path).
- **Per-person view: show currencies side by side, never net across them.** Max's state is a small
  set of signed per-currency balances ("you owe €10; Max owes you 10 CHF"). A base-currency total
  alongside (`Σ balance_base`) is a *supplementary* gloss; the per-currency figures are the truth
  and the settlement basis.
- **Settle-up is the one place currencies meet** — a real, dated, rate-stamped cross-currency
  transaction (§6.4) that zeroes the accounts; it balances in base like any conversion, and a genuine
  over-determined residual is entered by hand into `FX gain/loss` (§6.3, no auto-booking).

---

## 8. Integrity invariants (the tests)

These are the cheap, high-value checks — written as native SQL, run against real Postgres
(Testcontainers), doubling as the living spec. Optionally also backed by a constraint trigger; at
minimum they are tests.

1. **Sum-to-zero, conditional on currency mix.** For each live transaction:
   - all postings share one currency ⇒ `sum(amount) = 0`;
   - postings span >1 currency ⇒ every leg has a non-null `base_amount` and `sum(base_amount) = 0`.
   (Equivalently: `sum(coalesce(base_amount, amount)) = 0` *when* a single currency makes `amount`
   directly summable; the two-branch form above is the unambiguous statement.)
2. **Leaves-only (accounts).** No posting references an account that is some other account's
   `parent_id`. (Tags are exempt — §10 — they are deliberately *not* leaves-only.)
3. **Currency consistency.** A posting's implied currency is `account.currency_code`; cross-currency
   detection is by `count(distinct account.currency_code)` over the transaction's postings.
4. **Soft-delete coherence.** A live posting belongs to a live transaction; deleting a transaction
   deletes its postings. All invariants scope to `deleted_at is null`.
5. **Backdated-insert balance correctness** (from tech-stack §4.2) — running balances of rows above
   a backdated insert are corrected. Explicit test required.

---

## 9. Computed on the fly vs stored

| Quantity | Strategy | Why |
|----------|----------|-----|
| Account running balance | **Computed** (windowed `sum` over postings) | Fast for one user's ledger; a stored balance is a drift risk. Add a cache only if measured slow; month-end granularity if so. |
| Base-currency value of a flow | **Computed** (posting × rate@its date, or frozen `base_amount`) | Rate edits propagate; history honest. |
| Net worth / balance in base | **Computed** (`native_balance(D) × rate@D`) | Mark-to-market; one multiply; no snapshot. |
| `base_amount` of a single-currency posting | **NULL / not stored** | Derivable; lets past-rate edits refresh reports. |
| `base_amount` of a cross-currency posting | **Stored, frozen** | A real event's real rate; must never be recomputed. |
| Exchange rates | **Stored** (sparse, carry-forward) | Input + held-balance revaluation; manual override. |

---

## 10. Tags

An **orthogonal, overlapping** label dimension — distinct from categories (which are accounts, one
per posting). Tags do the per-car / per-trip slicing (`Car:Audi`, `Trip:Prague2026`).

### 10.1 Tables

```sql
create table tag (
  tag_id     bigint generated always as identity primary key,
  name       text not null,
  parent_id  bigint references tag(tag_id),    -- self-ref; hierarchy (NULL = top level)
  deleted_at timestamptz
);

create table posting_tag (
  posting_tag_id bigint generated always as identity primary key,
  posting_id     bigint not null references posting(posting_id),
  tag_id         bigint not null references tag(tag_id),
  unique (posting_id, tag_id)                  -- a tag applies to a posting at most once
);
```

- **`tag` is a real entity, not a bare string.** A string can't carry identity: the rules engine
  (FR-TAG-05) and learned mappings need a stable `tag_id` to key auto-application off; **merge**
  (§5.18) needs two ids to merge into one; rename is a single-row update, not a denormalized
  text-rewrite; and a retyped `Audi ` / `audi` would silently fork. Same reasoning as `payee`.
- **Surrogate keys throughout** (§3.0): `posting_tag` has its own `posting_tag_id`; the natural pair
  is the `unique` constraint (and the natural FK target if an assignment ever needs metadata, e.g.
  "applied by the rules engine vs by hand").

### 10.2 Tags attach to the **posting**, not the transaction

Forced by FR-TAG-04: a fuel line tagged `Car:Passat` and a snacks line tagged `Trip:Prague` can sit
on the **same** split transaction, so the tag must land per-line. A tag slice is then "sum these
postings" — the same flow-rule shape as a category report, so multi-currency aggregation in base
(§6.5) falls out for free. (Applying a tag to a whole transaction in the UI is a fine input
convenience; it just expands to one `posting_tag` row per leg.)

### 10.3 Hierarchy: present and live, but **not** leaves-only

`parent_id` makes `Car` a real tag with `Car:Audi`, `Car:Skoda` as children. Unlike accounts (§5),
**a posting may be tagged with a parent directly _and / or_ with leaves** — there's no sum-to-zero
invariant to protect, and "tag it just `Car` when I don't care which car" is a legitimate, common
case. So `Car` is simultaneously a taggable label and a rollup.

**The rollup rule — aggregate _distinct postings_, never summed assignments.** This is the one place
it's easy to get a wrong total. A posting carrying **both** `Car:Audi` and `Car:Skoda` must count
**once** in a `Car` report, not twice. So a tag rollup selects the distinct postings whose tags fall
anywhere in the tag's subtree, then sums those — it never sums `posting_tag` rows:

```sql
-- "Car" report: each matching posting once, even with multiple Car:* tags or Car itself
select sum(p.amount)                       -- × rate per §6.5 for base-currency reporting
from posting p
where exists (
  select 1 from posting_tag pt
  where pt.posting_id = p.posting_id
    and pt.tag_id in (:car_subtree_ids)    -- Car + all descendants, via recursive CTE
);
```

The separate `Audi` and `Skoda` reports run the same shape with their own single subtree, and the
posting legitimately appears in **both**. So the same posting counts **once within** a report and
**once per** report — exactly the desired behaviour. (`Car` subtree ids come from a recursive CTE
over `tag.parent_id`.)

### 10.4 Tags are overlapping lenses — do not total across tags

A posting tagged `Car:Passat` **and** `Trip:Prague` appears in both the car report and the trip
report; that's correct, tags are not a partition. Consequence: a "spend by tag" matrix has **no
meaningful grand-total column** the way the category matrix does — summing across tags double-counts
every multiply-tagged posting. Per-tag figures are valid; a total across tags is not.

---

## 11. Open / deferred model questions

| # | Question | Status |
|---|----------|--------|
| T-DM-1 | `id` strategy — `bigint` identity vs UUID | Open; identity shown as default, low-risk either way |
| T-DM-2 | Enforce sum-to-zero as a constraint trigger, or test-only | Lean test-only (legibility); trigger optional |
| T-DM-3 | `FX gain/loss` filed under income or expense in the matrix | Cosmetic; single signed leaf either way |
| T-DM-4 | Opening-balance mechanics via an `equity` *Opening Balances* account | Confirmed shape; detail when importer is built |
| T-DM-5 | Whether per-person base-currency total is shown at all (vs per-currency only) | Lean: per-currency is truth, base total supplementary |
| T-DM-6 | `exchange_rate` surrogate PK vs composite natural PK | Surrogate for convention uniformity; revisit if it ever grates |

---

## 12. Not modeled here yet (explicitly deferred)

These are real entities from the requirements, intentionally **out of scope for this core doc** and
to be designed next:

- **Recurring templates & subscriptions** (§5.3, §5.5) — generate `pending_review` transactions.
- **Budgets** (§5.13) — on the same category (account) taxonomy and/or tags; no separate budget
  table of categories.
- **Attachments** (receipts/statements on the Pi filesystem, ARCH-07) — linked to transactions.
- **Holdings / positions** (§5.11) — contribute to net worth via the same `native × rate@D` rule,
  with manually-entered "rates" (prices).
- **Import canonical representation** (§5.12) — targets this model; idempotency keys live there.

---

### Decisions captured this round (summary)

- **Naming convention:** every entity has a surrogate PK `<entity>_id`; FKs reuse the target's PK
  name. Exceptions: externally-defined entities keep their natural key (`currency.currency_code`);
  self-references use `parent_id`. Junctions get their own `<junction>_id` + `unique` on the pair.
- **Tags** — classical m2m on the **posting** (`tag` + `posting_tag`); `tag` is a real entity (not a
  string); hierarchy via self-ref `parent_id`, **not** leaves-only (parent tags are directly
  taggable); rollups aggregate **distinct postings** over the tag subtree; tags overlap, so never
  total across tags.
- Single-sided **signed postings**; `+ = debit`, `− = credit`; **transaction carries no amount**.
- **Categories are backed by accounts** (income/expense types); no separate category table — a
  category is a set of same-named per-currency `income`/`expense` accounts, kept consistent by the
  `categories` module.
- **Root `settings` entity** (§3.8) — single-row; **write-once `base_currency`** (required before any
  transaction, immutable thereafter) lives in the DB, not config; plus the `display_name`.
- **Categories are per-currency at the leaf** (`Food-CHF`, `Food-EUR` under `Food`) — currency
  determined by the payment, not chosen; rollups in base via the flow rule.
- **Leaves-only** posting (accounts); parent balance = sum of descendants; subdivision is a domain op.
- **`lifecycle` and `deleted_at` orthogonal**; soft-delete reversible.
- **Per-person debts** = one signed `asset` account per (person, currency), linked via
  `account_owner`; `beneficiary_id` dropped.
- **Two valuation rules**: flows posting-wise (frozen history), balances `native × rate@D`
  (mark-to-market).
- **FX policy #3**: holding-period FX in net worth only; P&L FX-blind. `FX gain/loss` (single signed
  leaf) is a **manual** account — no auto-booking; an over-determined conversion is refused by the
  sum-to-zero invariant and the user resolves it by hand.
- **`base_amount` nullable**: `NULL` = derive on the fly; non-null = frozen cross-currency fact.
- **No materialized balances, no universal materialized base_amount** — derive until measured slow.
- **Conditional sum-to-zero** invariant (native for single-currency, base for cross-currency).