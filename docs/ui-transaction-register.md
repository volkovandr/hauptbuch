# Personal Finance Manager — UI: Transaction Register & Entry Dock

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.3
**Date:** 2026-07-11
**Owner:** volkovandr
**Companion to:** `requirements.md` (v0.4),
`tech-stack.md` (v0.1),
`data-model.md` (v0.5)

> This document records the **interaction design** of the two central surfaces — the transaction
> **register** (the list) and the **entry/edit dock** (the persistent form) — together with the
> reasoning behind each decision, in keeping with the house rule that the *why* must survive long
> after the *what* is code.
>
> Scope note: this covers layout, what a row means, the column/colour/balance rules, and the dock's
> field behaviour (payee/category/tag pickers, the autofill rule, sign-free amount entry, splits,
> notes). The **exact keyboard state machine** (per-field key maps, picker open/closed transitions,
> focus capture) is **deliberately deferred to implementation** — more decisions surface there, and
> pinning them now would be premature.

**Changelog**
- **v0.4 (2026-07-13):** Redesigned **cross-currency splits** (§3.8a, §3.10). A split spans **at most
  two currencies** (funding + spending), chosen **once at the header** with one shared rate — not the
  per-line currency of v0.3. Each line is a **single spending-currency amount**; its account and base
  equivalents are derived and shown read-only per line; only **base** sums to zero, and `remaining` is
  shown in every currency in play (all converge to zero together). Overturns v0.3's "same rule applies
  per split line (own native + base)".
- **v0.3 (2026-07-11):** Added the **category-currency selector** (§3.5): the leaf currency defaults
  to the paying account's currency but is selectable, and overriding it declares a cross-currency
  purchase. Added **cross-currency amount entry** (§3.8a): one amount field per distinct currency
  (1/2/3 fields via progressive disclosure), balanced in **base**, with the neither-side-is-base case
  taking a confirmable base amount from `rate_as_of`; the same rule applies per split line. **No FX
  field** — the engine books no residual (data-model §6.3); an over-determined transaction is refused
  with the base gap shown, for the user to close with a manual `FX gain/loss` line.
- **v0.2 (2026-06-22):** Reworked the per-person debt display rule (§2.6): the **column** of a
  person's leg is set by whether one of *your own* accounts moved, and the **arrow direction** is set
  independently by the flow (`Max →` = from Max / you owe; `→ Max` = to Max / Max owes). This
  separates "Max paid for my expense" (`Max →` in **Account**) from "Max lent me cash" (`Max →` in
  **Category**). Added **sign-free amount entry** (§3.8): the amount is a bare magnitude; the chosen
  counterpart (the "category") sets the direction; an explicit `+`/`−` overrides it for
  refunds/reversals. Narrowed Q-UI-1 accordingly.
- **v0.1 (2026-06-22):** Initial interaction design. Newest-at-bottom register; row = one posting to
  a viewed account; the Account-vs-Category display rule incl. transfers and per-person debts;
  per-account running balances; two-tone-per-account colour; currency display; persistent bottom
  dock with entry-ordered fields; payee/category/tag pickers with create-new parsing; the
  amount-before-category autofill rule (single suggested category, never a replayed split); split
  panel with "the rest" defaulting; tag inheritance into splits; notes at transaction and split
  level.

---

## 1. Inherited principles

From the requirements (§5.0) and the tech-stack/data-model docs, applied here:

- **Dense, inline, numbers-first, no modal entry.** Thin single-line rows; figures always visible;
  routine entry/edit happens inline, not in a dialog (FR-UX-01…06).
- **Keyboard-first** on desktop (NFR-01) — every action reachable without a mouse. (The concrete
  key map is implementation-time work.)
- **The model is uniform; the *display* and the *entry* add the conveniences.** The data model has
  one shape — signed postings, categories-as-accounts, per-person debts as signed accounts,
  `beneficiary_id` dropped. None of the register's niceties (category summaries, debt arrows,
  transfer rows) and none of the dock's conveniences (sign-free amounts, ghost categories) are new
  model concepts; they are **presentation and input rules** layered over the uniform posting model.
- **The counterpart carries the semantics, so the user supplies less.** Because a "category" is
  literally the other leg's account, its *type* already implies direction (debit/credit). The UI
  exploits this everywhere: the user picks the counterpart and a magnitude, and the model fills both
  signed legs (§3.8).

---

## 2. The register

### 2.1 Reading direction — newest at the **bottom**

Oldest at top, newest at the bottom; the running balance accumulates **downward**; you scroll **up**
to see older rows. The entry dock sits at the bottom, next to the newest row and next to where new
rows land — the eye is already there.

**Rationale.** This is the natural ledger / checkbook direction and it co-locates "now" (latest
balance) with the entry point. It diverges from typical web "newest on top" lists, deliberately.

**Implementation notes** (ref tech-stack §4.2):
- Insert is `hx-swap="beforeend"` into the `<tbody>` — one DOM node, cost independent of list length.
- Scroll-to-bottom on load (`scrollTop = scrollHeight`, or a bottom anchor element).
- Bottom-anchoring means "newest" **only while the sort is date-ascending**; any other sort detaches
  that meaning (see §2.7).

### 2.2 Backdated-insert balance correctness

With newest-at-bottom and a per-row running balance (balance *as of* that row), a **newest** row
only changes the bottom balance (trivial). A **backdated** row shifts the running balance of every
row *more recent* than it — i.e. the rows **below** it. The affected slice is "insertion point →
bottom." Resolve via OOB slice-swap (preferred) or bounded re-fetch (acceptable); explicit test
required (data-model §8 invariant 5, tech-stack §4.2). This is the mirror image of the newest-first
layout's "→ top" slice.

### 2.3 Filters & ordering

| Aspect | Default | Notes |
|--------|---------|-------|
| Date range | **Last 12 months** | The natural bounded view; keeps render + worst-case re-fetch to hundreds of rows. |
| Accounts | **Current bank accounts + cash** | Multi-account by default (Money showed one at a time — rejected, see §2.5). |
| Payees | none (all) | Free filter. |
| Order | **Date, ascending** | Changeable; non-date sorts change balance behaviour (§2.7). |

### 2.4 What a row is

**A row is one posting to an account you are currently viewing, placed in date order.** Everything
else about the row is derived from that posting's siblings in the same transaction. This single
definition makes splits, transfers, and debts fall out without special cases.

- **Ordinary expense** (e.g. `Cash −30, Food +20, Max +10`) → **one** row: the Cash leg. Payee from
  the transaction; the Category column summarises the *other* legs; Amount is the Cash leg; Balance
  is Cash's running balance.
- **Transfer between two viewed accounts** → **two** rows, one per leg, each its own colour and its
  own correct balance. Filter to a single account and only its leg shows. No "transfer" code path —
  it is just "rows = postings to the viewed accounts."

### 2.5 Columns

Left-to-right: **colour spine · Date · Account · Payee · Category · Amount · Balance · status**.

- **Column order is fixed and stays as-is.** It is optimised for *reading* (Category sits left of
  Amount). The dock's field order differs (§3.2) and that is fine — the dock is a visibly distinct UI
  element and is felt as one.
- **Responsiveness:** Date is **always fully visible**; **Category** is the first column to drop on a
  narrow viewport. Intended primary target is a Full-HD screen at full width, where nothing drops.
- **Amount is *entered* sign-free but *displayed* signed.** Entry supplies a bare magnitude (§3.8);
  the register shows the resolved sign (outflow negative, inflow positive) because the running
  balance needs it and it scans fast.

### 2.6 The Account-vs-Category display rule (transfers and debts)

This is the one genuinely subtle presentation rule. Each transaction is shown through a **primary
(funding) leg** and its **counterpart legs**, and for per-person debts the **column** and the
**arrow** are decided by two *independent* questions.

**Which column does each leg occupy?**
- **Primary leg → the Account column.** The primary leg moves **one of your own accounts**
  (asset/liability: Cash, Giro, Visa, **and a person's debt account** — data-model §7). If more than
  one such leg is in view (a transfer between two viewed accounts, or a person leg alongside a cash
  leg), the transaction yields **one row per such leg** (§2.4); each row's *own* leg is its Account.
  Where a single leg must be named the funding/edit anchor (the dock on reopen, the Category summary),
  it is the **most-negative own leg** — the largest outflow — which generalises the transfer sign rule
  to any number of own legs (tie among equal magnitudes → the credit leg wins).
- **Counterpart legs → the Category column.** Income/expense legs show as the category name (biggest
  wins, with an overflow hint like `Food · +2`); another of your real accounts shows as a
  **direction-arrowed transfer** — `→ Account` when funds went **to** it (its leg is a debit),
  `← Account` when they came **from** it (its leg is a credit); a person's debt leg shows as an
  **arrow chip** (below). Each row of a transfer shows the *other* leg, so a transfer between two
  viewed accounts reads `→ Visa` on the Cash row and `← Cash` on the Visa row.
- **When no *real* own-account leg is in view** — a pure expense or income that a *person* funded (no
  Cash/Giro/Visa leg, only the person's leg and a category) — the **person's debt leg is the primary**
  (it is the most-negative own leg by default) and occupies the **Account** column.

**Which way does the arrow point?** Independent of the column, set by the flow alone:
- **`Max →`** = funds came **from** Max (Max is the source); **you owe Max** more.
- **`→ Max`** = funds went **to** Max (Max is the destination/beneficiary); **Max owes you** more.
- Equivalently, by the `+ = debit` sign of Max's posting: **debit (+) → `→ Max`**, **credit (−) →
  `Max →`**.

Because column and direction are decided separately, **`Max →` can appear in either column**:

| What happened | Postings (example) | Account column | Category column |
|---------------|--------------------|----------------|-----------------|
| You front money for Max, or **lend Max cash** (your cash goes out) | `Cash −10`, `Max-EUR +10` | `Cash` | `→ Max` |
| You buy for yourself **and** Max | `Cash −31,50`, `Food +21,50`, `Max-EUR +10` | `Cash` | `Food · → Max` |
| **Max pays for an expense of yours** — you consumed it (no cash of yours moves) | `Food +10`, `Max-EUR −10` | `Max →` | `Food` |
| **Max lends you cash** (your cash goes in, no expense) | `Cash +10`, `Max-EUR −10` | `Cash` | `Max →` |

The key contrast the model now captures: *Max buying your groceries* is an **expense** of yours
funded by Max → `Max →` in **Account**, `Food` in **Category**. *Max lending you cash* is **not** an
expense → `Cash` in **Account**, `Max →` in **Category**. Same debt direction (you owe Max), different
transaction, different display.

Per-person debts still have **no special mechanism** — every line above is just an ordinary posting to
Max's signed per-currency debt account (data-model §7). The arrows are pure display of leg direction;
the **net** standing with Max is still the **sign of Max's running balance** (positive = Max owes you;
negative = you owe Max).

> **Resolution (Q-UI-1) — surfaced.** The third pattern — *Max pays for a pure expense of yours* —
> has **no bank/cash leg**, but a person's debt leg is an ordinary `asset` account, so it **is** in
> the default viewed set (§2.3): the transaction **does** appear in the default register, with the
> person on the **Account** side and its own running balance shown (a real balance, like a credit
> card's). Person accounts are not special-cased out of the register — they are assets like any other.

### 2.7 Per-account running balance

The **Balance** column is the running balance of **that row's own account** — never an aggregate
across accounts. Aggregating a running balance across accounts (worse, across currencies) reconciles
against nothing.

Because balances are per-account and rows are interleaved by date, the Balance column is **not** one
smooth sequence — it is one sequence **per account**. That is precisely what makes colour-by-account
(§2.8) functional rather than decorative: the tint tells you which balance thread a number belongs
to.

**Balance is meaningful only in date order.** When the user sorts by amount, payee, or any non-date
key, the Balance column is **hidden** (not greyed) — an out-of-order "balance after" would be
misleading.

### 2.8 Colour model — two shades per account, stored

- **Colour encodes the account.** With the default filter (current accounts + cash) this naturally
  lands on the "two / three / four colours" the owner expected — because the colours *are* the
  default account set.
- **Each account carries two shades** — a brighter and a darker tint of the **same** stored hue —
  alternated row-to-row as a zebra. This separates adjacent rows *and* preserves account identity
  simultaneously (a same-hue zebra rather than a neutral one).
- **The hue is a stored property of the account**, assigned once, **not** picked dynamically from the
  current filter. Giro is the same blue whether or not Visa is on screen.
- **Text colour stays restrained:** black / grey by default; **green** for income amounts; **red**
  for negative *balances* (you are "in the red", e.g. a credit-card thread). Expenses are neutral
  (the leading minus carries them) so the list does not become a wall of red.

### 2.9 Currency display

- **Base currency (EUR) is rendered bare** — just the number, German-formatted (`1.234,56`).
- **Non-base accounts/transactions carry their currency symbol** (e.g. `$`, `£`; ISO code such as
  `CHF` where there is no clean glyph).
- A non-base account is simply another colour with its own native balance thread; **the list never
  aggregates across currencies** (consolidation in base is a reporting concern, not a register one).

### 2.10 Lifecycle & status indicators

- **`pending_review` rows** (recurring pre-registrations, review-pending captures) render **muted /
  dashed** and show **no balance** (`—`) — they do not move the confirmed balance.
- Small trailing **status icons**: reconciliation state (unreconciled / cleared / reconciled),
  a **receipt** paperclip when an attachment is linked, a **recurring/subscription** marker, a
  **pending** clock. Kept subtle (FR-UX-04).

---

## 3. The entry / edit dock

### 3.1 One persistent bottom dock

A single form docked at the **bottom**, always present. **New mode** by default; selecting a row
loads that row into the dock (**edit mode**) and saving **updates it in place**. The dock returns to
new mode after a commit.

**Rejected:** expand-in-place (the selected row grows into an editor). It is more spatially coherent
when editing a row far up the list, but it makes row heights jump and complicates scroll-anchoring,
and most edits are of recent (near-bottom) rows anyway. The bottom dock wins.

### 3.2 Field order — optimised for entry, not for reading

Dock order: **Date · Account · Payee · Amount · Category · Tags · Note · Add.**

This **intentionally differs** from the register's column order (which keeps Category left of Amount
for reading). The dock is a distinct UI element and is felt as one, so the mismatch is a non-issue.
The order is driven by the autofill rule (§3.9): the amount is entered **before** the category
commits.

### 3.3 Account chosen at entry; re-threading on change

The Account is picked **as you type**, eliminating Money's "right-click → move to account…" dance
(the multi-account register plus an Account field makes the destination a first-class entry field).
Editing an existing row's Account **re-threads** it: it re-colours, and both the old and new
account's running balances **from that date downward** recompute — the same slice machinery as a
backdated insert (§2.2).

The Account field accepts a **person debt account** as well as a real account — that is how the "Max
paid for an expense of mine" case (§2.6) is entered: Account = `Max →`, Category = the expense.

### 3.4 Payee picker & create-new

- **Match key** = the **normalised concatenation** of name + city + country (lowercased, separators
  stripped). So `rewedort` fuzzy-matches `rewedortmundgermany` and ranks `Rewe · Dortmund · Germany`
  first.
- The dropdown **always** carries a keyboard-reachable **"Create new payee"** as its last item — so
  even when a near-match exists, you can pass it and make a fresh one (the "it's there but slightly
  different" case).
- **A payee is Name + optional City + optional Country.**

**Create-new parsing — how city is told from country.** The typed string is split on separators
(` - `, `,`):
- **First** segment → **Name**.
- **Last** segment is validated against a **seeded country reference list** (ISO 3166 names + common
  aliases, incl. German — `France`/`Frankreich`/`FR`, `Germany`/`Deutschland`). Match → **Country**.
- Any **middle** segment → **City**.
- A lone ambiguous segment defaults to **City**, with the create-form open one `Tab` from moving it
  to Country.

Worked: `Lidl - France` → Name `Lidl`, City —, Country `France` (matched). `Rewe - Dortmund -
Germany` → City `Dortmund`, Country `Germany`.

The country list is **small, stable, offline** — seeded like the currency table. **Cities stay free
text** (no gazetteer). The pre-filled mini-form is the safety net for any mis-classification.

### 3.5 Category picker & create-new

- Same picker pattern as payee.
- **Create-new parsing:** `Parent - Child` → **Parent** must resolve to an **existing** account;
  **Child** is the new leaf; **Type is inherited** from the parent (`expense`/`income`). Example:
  `Food - Milk` → parent `Food`, new leaf `Milk`, type `expense`.
- **Currency defaults, but is selectable.** The per-currency leaf (`Milk-EUR` etc.) **defaults to the
  paying account's currency** (data-model §6.5) — the single-currency path, where the user picks the
  category *semantically* and never touches currency. A **currency selector beside the category** lets
  the user override it to another currency; doing so **declares the transaction cross-currency** and
  reveals the extra amount field(s) (§3.8). On a **transfer** both legs are real accounts, so their
  currencies are fixed and no selector is shown.
- **Subdivision tie-in:** if the chosen parent was itself a **leaf** (or had only hidden currency
  leaves, data-model §6.5), creating a child under it is exactly the §5 **subdivision domain
  operation** — the parent is promoted and its existing postings (and currency leaves) move to an
  `Uncategorized` sibling. No new machinery.
- **The category type drives entry direction** (§3.8): choosing an expense vs income (vs `→ Person` /
  `Person →`) counterpart sets the sign of the funding leg, so the amount stays sign-free.
- **Person attribution (`for` / `by`).** Typing `for <person>` books the person as the counterpart
  **you funded** (`→ Person`), `by <person>` the person who **funded it** (`Person →`) — the reserved
  `for`/`by` keywords parallel the `to`/`from` transfer keywords and dodge the person-vs-account name
  clash. A person leg **is** a transfer to/from that person's per-currency leaf (data-model §7), so it
  rides the transfer commit path; the currency selector applies as above (funding-account currency by
  default, override → cross-currency, e.g. settling a EUR debt with USD cash). An unknown name
  **creates** the person, with a confirm when the name would instead **revive** a soft-deleted one
  (data-model §7). The **Account** field also accepts a person — surfaced by typing, not listed by
  default — as the *funding* leg for the "they paid a pure expense of mine" case (§2.6, the resolved
  Q-UI-1). Both the Account and Category picker labels carry a tooltip listing these shortcuts.

### 3.6 Tags — chip field with split inheritance

- **JIRA-style chip field:** type → `↓` to pick → commit a chip → **cursor stays** for the next tag.
  `:` is the hierarchy separator (`Car:Passat`). Backspace on an empty field removes the last chip.
  Unknown text offers create.
- **Inheritance into splits:**
  - If **transaction-level tags were set** (before splitting), **all split lines inherit them**.
  - If **not**, each **new split line inherits the previous line's tags**.

  (Tags live on postings in the model, so a transaction-level tag simply expands to one assignment
  per leg — data-model §10.2.)
- **The funding leg carries the transaction-level (dock) tags only — never the per-line ones.**
  This is not a new concept: the dock's Tag field is transaction-level and already tags *every* leg
  (funding included) in the simple single-line case; a split just keeps that. So a split resolves to:
  - **funding leg** = the dock (transaction-level) tags;
  - **split line _i_** = the dock tags **+** that line's own chips.

  Rejected alternatives: *no tags on the funding leg* (drops the dock tags too — inconsistent with
  single-line, where the funding leg does carry them); *the funding leg gets the distinct union of
  all line tags* (dead data — tag reports filter to flow legs, so the union is never summed; it
  makes the shared leg falsely claim every line's slice; and it destroys the edit round-trip, below).
- **Edit round-trip falls out cleanly:** on reopen, the funding leg's tag set *is* the dock field,
  and each line's chips *are* that line's tags — no reverse-engineering of which tags were
  transaction-wide. Line chips pre-fill with the inherited dock tags (visible and removable per line),
  so a tag can be dropped from one line without affecting the others.
- **Edge case (intended):** if only per-line tags are set and the dock field is left empty, the
  funding leg carries no tags — the user made no transaction-wide statement. This is correct, and
  distinct from the rejected "no funding-leg tags" option, which would strip dock tags that *were* set.

### 3.7 Notes — transaction **and** split level

A **note** is available at the **transaction** level (the dock) **and** on **every split line**
(posting). These map directly to `transaction.note` and `posting.note` in the data model — no model
change.

### 3.8 Sign-free amount entry — direction comes from the counterpart

The **Amount is typed as a bare magnitude**; no sign in the common case. Because the "category" is
literally the *other leg's account*, the **counterpart's type fixes the direction** of the funding
leg (and therefore both signed legs):

| Counterpart chosen | Funding-account leg | Example |
|--------------------|---------------------|---------|
| **Expense** category | outflow (−) | `Food` → `Cash −` |
| **Income** category | inflow (+) | `Salary` → `Giro +` |
| **`→ Person`** — typed `for Max` (you funded it) | outflow (−) | `→ Max` → `Cash −`, `Max +` |
| **`Person →`** — typed `by Max` (they funded it) | inflow (+) | `Max →` → `Cash +`, `Max −` |
| **Transfer** to another own account | outflow from the entry account | `⇄ Visa` → entry account `−`, `Visa +` |

The sign **resolves the moment the counterpart is known**. Thanks to the payee→category ghost
suggestion (§3.9), that is usually immediate — you often see the resolved sign before finishing the
amount.

**Explicit sign overrides — required, not polish.** A leading `+`/`−` typed before the magnitude
forces the funding-account direction, overriding the counterpart default: `−` = funds leave the
account, `+` = funds enter it. This exists because **refunds and reversals are inversions the
counterpart type can't express** — a refund is an *inflow to an expense category* (return the food,
get cash back), and without an override a sign-free scheme simply cannot represent a negative-expense.

```
Normal expense :  Food  +  "10"   →  Cash −10, Food +10
Refund         :  Food  +  "+10"  →  Cash +10, Food −10   (override: funds enter Cash)
```

**Rationale.** The counterpart account's type already determines which leg is debit vs credit, so
re-typing a sign is redundant data the user could get wrong. Sign-free entry removes a whole class of
sign errors and keeps entry fast and keyboard-light; the rare inversion opts in with one character.

> **Display note (Q-UI-6).** The register still *shows* the resolved sign (§2.5). Whether to also
> suppress the leading minus on expense rows in the *display* (since the category already conveys
> direction) is left open; leaning keep, low-stakes.

### 3.8a Cross-currency entry — one amount per currency, balanced in base

When the counterpart's currency differs from the funding account's (the category-currency selector
was overridden, §3.5, or a transfer targets a differently-denominated account), the transaction is
**cross-currency** and the single Amount field **splits into one field per distinct currency present**
— because the legs no longer sum to zero natively; they must balance in **base** (data-model §6.4).

- **One foreign side, base present** (EUR card → CHF food): **two** fields — the paying account's
  amount and the counterpart's amount. The base-currency leg's `base_amount` equals its own amount, so
  no separate base field is shown.
- **Neither side is base** (CHF card → USD goods, base EUR): **three** fields — each native amount
  plus a single **base amount**. The base is **pre-filled from `rate_as_of`** on one leg and is
  **confirmable/editable**; it is frozen on both legs so `Σ base_amount = 0`. The implied cross-rate
  may be shown read-only, but is **never written back to the `exchange_rate` feed** (data-model §6.4:
  the frozen transaction fact and the revaluation feed are separate sources).
- **The amounts are irreducible facts, not clutter.** Three fields means three real numbers (you paid
  USD, the goods cost CHF, it is worth EUR); progressive disclosure keeps them hidden until a currency
  actually diverges, so the ≥95% single-currency path stays a single field.
- **No FX field, ever.** The engine books no residual (data-model §6.3). If the entered legs don't
  balance in base — only possible when reality over-determines them — the save is **refused with the
  base gap shown**, and the user adds a manual `FX gain/loss` line to close it.

In **splits** the currencies are fixed **once, at the header** — never per line. A single receipt is
one merchant billing one currency, paid from one account at one rate, so a split spans **at most two
currencies** (funding + spending) with a **single shared rate**; the header carries the same 1/2/3
total amount fields as a single line (above). Each split **line** then takes a **single amount** — the
spending-currency figure on the receipt — and its account-currency and base equivalents are **derived**
from the shared rate and shown **read-only per line**. Only the **base** amounts sum to zero in the
ledger (the legs are in different currencies); the panel shows `remaining` in **every currency in
play**, all reaching zero together. "The rest" defaulting extends to base, so the last line absorbs the
rounding residual and `Σ base_amount = 0` holds exactly. A receipt that genuinely mixed currencies
would be two transactions.

### 3.9 The autofill rule (the core behaviour)

The decision that fixes Money's most annoying entry pattern:

1. Accepting a **payee** surfaces its **most-common category** as a **ghost suggestion** — a single,
   *uncommitted* line. It is the statistical **mode** for that payee (a fuel station → `Fuel`).
2. The suggestion is **never a replayed split.** Money restored the *previous split*, which then
   mis-balanced the moment the amount changed, forcing the `Ctrl+Tab`-into-stale-split dance. That is
   eliminated at the root: structure is never auto-restored.
3. The **amount is entered before** the category commits, so by the time the category locks the
   figure is known and the single suggested line is correct.
4. The suggestion commits on the user's accept (`↵`/`Tab`); overriding it is **one keystroke**.
5. To split, press **`S`** — which takes the single committed line at full amount into the split
   panel (§3.10). A split is therefore always a **deliberate** act, never an inherited surprise.

### 3.10 Split panel

- **Inline-expand of the dock, not a modal.** This honours the no-modal-entry principle (FR-UX-01);
  a true modal is the **fallback** only if the inline panel ever feels cramped.
- **"The rest" defaulting:** each new line's amount defaults to **total − already-allocated**, so the
  last line always closes the gap. The split therefore **balances by construction** and the
  posting-level **sum-to-zero invariant holds automatically**; a `remaining 0,00 ✓` readout confirms
  it.
- **Each line carries** category + optional tags (§3.6) + optional beneficiary (`→ Person`) +
  optional note (§3.7). A line may instead be a **transfer** to another own account (`To →`/`From ←`,
  §3.5/§3.8), signed by its direction; its account must be in the split's spending currency (the
  header's single shared rate spans at most two currencies, §3.8a).
- **Cross-currency splits carry their currencies at the header, not per line** (§3.8a): the funding
  account and the one spending-currency selector fix both currencies and the shared rate once; each
  line is a single spending-currency amount with its account-currency and base equivalents derived and
  shown read-only per line, and a `remaining` readout per currency in play (all reach zero together).
- **Result visible without extra clicks:** on commit the register's Category cell shows the **top
  one-to-three** categories (e.g. `Food · → Max`).

---

## 4. Decisions & rejections summary

| Area | Decision | Rejected / alternative | Why |
|------|----------|------------------------|-----|
| Reading direction | Newest at bottom | Newest on top | Natural ledger direction; co-locates latest balance with entry. |
| Row identity | One posting to a viewed account | Transaction-centric row | Splits/transfers/debts fall out with no special cases. |
| Transfers | Two rows (one per leg) | Single collapsed row | A single row can't show a per-account balance for two accounts. |
| Balance | Per-account; hidden off date-sort | Aggregated running balance | Aggregate reconciles against nothing; out-of-order balance misleads. |
| Colour | Account hue, two shades, **stored** | Positional / dynamic colour; income-vs-expense colour; plain zebra | Same-hue zebra separates rows *and* keeps account identity; stored = stable. |
| Debt display — column | Own-account leg present → person leg is the Category; absent → person leg is the Account | Fixed column for the person leg | "Max paid my expense" vs "Max lent me cash" must read differently. |
| Debt display — arrow | Direction by flow/sign: `Max →` = from Max (you owe), `→ Max` = to Max (Max owes) | Arrow tied to column | Decouples direction from placement; `Max →` can sit in either column. |
| Columns | Order fixed (Category left of Amount) | Reorder to match dock | Optimised for reading; dock is a separate element, mismatch is fine. |
| Entry form | One persistent bottom dock | Expand-in-place | Stable layout, plays with bottom-anchoring; edits are mostly recent. |
| Dock field order | Date·Account·Payee·Amount·Category·Tags·Note·Add | Mirror column order | Amount precedes category for the autofill + sign rules. |
| Amount entry | **Sign-free**; counterpart sets direction; explicit `+`/`−` overrides | Always type `−`/`+` | Counterpart type already fixes debit/credit; removes a class of sign errors. |
| Refunds/reversals | Explicit-sign override (required) | No override | Sign-free alone can't express a negative-expense (inflow to an expense). |
| Autofill | Single most-common category, never a split | Replay last split (Money) | Kills the stale-split `Ctrl+Tab` dance at the root. |
| Split panel | Inline-expand | Modal dialog | Honours FR-UX-01; modal kept only as fallback. |
| Split amounts | New line defaults to "the rest" | Manual balancing | Sum-to-zero by construction. |
| Payee create | Parse Name / City / Country; country list validates last segment | Gazetteer / freeform | Country list is small, stable, offline; cities stay free text. |
| Category create | `Parent - Child`, type inherited, currency defaults to paying account | Ask type/currency each time | Inherits from parent; leaf currency defaults to paying account, selector overrides for cross-currency (§3.5, §6.5). |
| Tag input | JIRA-style chips, cursor stays | One tag per commit | Fast multi-tag entry. |
| Tag→split | Inherit txn-level tags, else previous line | No inheritance | Avoids re-typing on every split line. |
| Notes | Transaction **and** split level | Transaction only | Maps to existing `transaction.note` + `posting.note`. |

---

## 5. Open / deferred questions

| # | Question | Status |
|---|----------|--------|
| Q-UI-1 | Should "a person funded a pure expense of yours" postings (no own-account leg) be surfaced in the *default* register, or only via the person filter / per-person view? (Cash-loan cases already appear, as they move Cash.) | Open (§2.6) |
| Q-UI-2 | Exact keyboard state machine (per-field key maps, picker open/closed transitions, `S` and split focus capture/release, the `+`/`−` override key handling) | **Deferred to implementation** — more decisions arise there |
| Q-UI-3 | "Most-common category per payee" — plain mode vs recency-weighted; tie-breaking | Lean: plain statistical mode; revisit if it mis-suggests in practice |
| Q-UI-4 | Status-icon set finalisation (which markers, glyphs) | Open; cosmetic |
| Q-UI-5 | Backdated-insert refresh: OOB slice-swap vs bounded re-fetch | Decide during register build (ties to tech-stack T2) |
| Q-UI-6 | Display: also suppress the leading minus on expense rows (category conveys direction), or keep it? | Open (§3.8); leaning keep |