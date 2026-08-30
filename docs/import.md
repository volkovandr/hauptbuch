# Personal Finance Manager — Import: Sessions, Maps & the QIF/Money Dialect

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.1
**Date:** 2026-08-30
**Owner:** volkovandr
**Companion to:** `requirements.md` (§5.12, FR-IMP-01–05),
`data-model.md`,
`ui-transaction-register.md`,
`implementation-plan.md` (§3, first bullet)

> This document records the **design of importing external data into the ledger** — the import
> session and its staging area, the three maps, the transfer-mirror rule, the commit gate, and the
> first concrete source format (**QIF, as written by Microsoft Money**) — together with the
> reasoning, in keeping with the house rule that the *why* must survive long after the *what* is code.
>
> Scope note: §2–§3 and §5–§10 are **format-agnostic** — they are the apparatus FR-IMP-01 asks for,
> and FR-IMP-05's generic CSV importer is expected to reuse all of it and supply only a new parser.
> §4 is the **QIF/Money dialect**, the first and currently only implementation of that parser. §11
> is a **provisional staging schema sketch**; §13 carries the slicing.
>
> This doc **settles Q9** (`requirements.md`): the Money export format is **QIF**.

---

## 1. The shape, in one paragraph

Importing 20 years of Microsoft Money is not one operation — it is a **campaign**. Money exports
**one account per file**, the owner has **100+ accounts**, and working through them takes weeks
during which Hauptbuch stays in daily use. So the import is modelled as a single long-running
**session** with a full **staging area**: files are parsed and staged, mappings accumulate,
loose ends are tracked, and **the ledger is not touched at all until the whole campaign commits
atomically at the end**. Volume is ~20–40k transactions (hard ceiling ~100k) from **2004-07-01**,
across ~300 Money categories, on a Raspberry Pi.

---

## 2. The import session and why staging exists

**One open session at a time.** Every uploaded file feeds the open session; a new session cannot
start while one is in progress. This is not a limitation to work around — it is what makes the
mirror rule (§6) and the commit gate (§9) definable at all.

Why nothing lands in the ledger until the end:

- **The register stays trustworthy for weeks.** The owner keeps entering transactions by hand
  throughout. Half-imported history interleaved with real entries would make the register unusable
  and every balance a lie, for weeks.
- **Mirror matching becomes a staging-local problem.** A transfer seen from account A's file and
  again from account B's file three weeks later is matched *within staging*, against data the
  importer itself produced — never by searching the live ledger, so it can never confuse itself
  with something the owner typed.
- **There is nothing to undo.** Review happens before anything is written, which removes the entire
  category of "reverse an import" machinery. A session that goes wrong is discarded, not unwound.
- **Cleanup is trivial.** Staging is cleared once, after a successful commit.

The session's ceremony is **backup → commit → backup**, using the existing backup feature. The
first protects against a bad commit; the second captures the new baseline.

---

## 3. The canonical import representation (FR-IMP-01)

A parser's only job is to turn a source file into **canonical records** — it never touches the
database, never resolves a name to an id, and never knows what a `posting` is:

- **`ImportedTransaction`** — date, payee text, memo, reference/cheque number, cleared flag,
  an opening-balance marker, and one or more lines.
- **`ImportedLine`** — a signed amount, a memo, and a **target** that is either a *category path*
  (a source-format string) or an *account reference* (a source-format account name, i.e. a transfer).

Everything downstream — mapping, mirror matching, staging, commit — operates on this shape alone.
That is the seam FR-IMP-05's CSV importer plugs into: a new parser, nothing else. It is also why
the parser is **pure Java with no Spring and no DB** and is tested entirely in the unit tier (§12).

---

## 4. The QIF/Money dialect

### 4.1 File shape

Money's *loose QIF* export writes **one account per file**, opening with a type header and then
`^`-terminated records. The file does **not** say which account it is — the owner states that at
upload (§5.1).

| Header | Meaning |
|---|---|
| `!Type:Bank`, `!Type:Cash`, `!Type:Oth A` | account is an **asset** (proposal, overridable) |
| `!Type:CCard`, `!Type:Oth L` | account is a **liability** (proposal, overridable) |
| `!Type:Invst` | **rejected** — see §4.5 |

### 4.2 Fields

| Field | Meaning | Lands as |
|---|---|---|
| `D` | date | `transaction.date` — see §4.3 |
| `T` | amount (`U` is a duplicate; ignored when equal) | the funding line's signed amount |
| `P` | payee | §5.3 |
| `M` | header memo | `transaction.note` |
| `N` | cheque / reference number | prefixed into `transaction.note` |
| `C` | cleared flag: `*`/`c` → `cleared`; `X`/`R` → `reconciled`; absent → `unreconciled` | `posting.reconciliation` |
| `L` | category path, or `[Account]` for a transfer, or `Category/Class` | §5.2, §6 — **and Q-IMP-1** |
| `A` | payee mailing address lines | **ignored** — the address comes from the payee-name parser (§5.3) |
| `S` / `E` / `$` | split: category / memo / amount | §7 |
| `^` | record terminator | — |

### 4.3 Dates

Money writes the Windows locale's short date with a Quicken-era twist — the year is separated by an
apostrophe: **`D26/11'2011`**. Separators `/`, `.`, `-` and `'` are all accepted.

QIF declares no date format, so it is **detected across the whole file**: if any first component
exceeds 12 the file is `DD/MM`; if any second does, `MM/DD`; if neither ever does, the file is
genuinely **ambiguous**. The upload preview states the detection *and its evidence* — "DD/MM,
proven by `31/03/2007` on line 412" or "AMBIGUOUS — no date in this file distinguishes them" — and
the owner **confirms or overrides** before anything is staged.

*Why this is not silent:* a day/month swap corrupts every date in the campaign, is valid-looking
for the ~68% of dates where both components are ≤12, and would surface years later, if ever. It is
the single worst failure mode this feature has, so it gets an explicit confirmation.

### 4.4 Encoding, and the `?` problem

Money always writes **windows-1252** and replaces every character it cannot represent with a
literal `?` (`0x3F`). The owner's Cyrillic **categories** are being renamed in Money before export;
**payees and memos cannot be** — there are thousands, spanning 20 years — so that text is
**destroyed at export time and is unrecoverable**. This is accepted, not solved.

The importer defaults to a strict UTF-8 decode and **falls back to windows-1252** when that fails,
with an override in the upload preview. UTF-8 and cp1252 agree only on ASCII (`0x00–0x7F`); every
byte `≥0x80` differs, so this is a real choice, not a shared path — and the strict-decode probe is
reliable because valid multi-byte UTF-8 sequences essentially never occur by chance in cp1252 text.
The preview shows the first ~50 decoded lines so mojibake is visible **before** 40,000 rows are
staged with corrupted text.

Consequences the importer must handle, not hide:

- A payee name that is **entirely** `?` and whitespace carries no information, and worse, *collides*:
  `Марс` and `Лена` both become `????`. Deduplicating those would fabricate a single merchant with
  the combined spending of twenty unrelated ones. So such a name yields **no payee at all**
  (`payee_id` null — already a valid state, transfers carry none), and the review reports the count.
- A **partially** destroyed name (`???????? Rewe`, `M?rs`) is still distinguishable and partly
  informative, and is imported **verbatim** as a real payee.

### 4.5 What is rejected

`!Type:Invst` is refused with a clear message naming the account. QIF's investment grammar is
entirely different (Buy/Sell/Div/ReinvDiv, quantities, prices, securities) and Hauptbuch has **no
investment support** — FR-INV-01/02 are "Could" and unbuilt. Importing positions that cannot then
be viewed, valued, or edited is worse than not importing them; a brokerage account imported as
cash-only would carry a balance that silently corrupts net worth.

---

## 5. The three maps

Maps are **session-scoped**, accumulate across files, and persist for the whole campaign — a
decision made while importing account A is still in force when account B arrives three weeks later.

### 5.1 Accounts

On upload the file is scanned and **every account name it mentions** is collected: the account
being imported (stated by the owner, since the file does not say) plus every `[Account]` transfer
counterparty. Mapping each one is **mandatory** before staging completes.

A Money account maps to **one of**:

- an **existing** Hauptbuch account,
- a **new** account (type proposed from the `!Type:` header, currency chosen by the owner — QIF
  carries no currency at all, so this cannot be inferred, only asked),
- a **person** (§5.4).

The map is **many-to-one**: several Money accounts may target one Hauptbuch account. That is both
the merge facility and, deliberately, the whole "junk account" story — legacy accounts the owner
never intends to export can all be pointed at one account they create for the purpose. No separate
catch-all mechanism is needed.

**`expect-file` flag.** Independent of the mapping, each Money account name carries "am I still
waiting for this account's own export?". Clearing it resolves that account's pending mirrors (§6)
and is the *only* escape hatch in the commit gate — a per-account, recorded, visible status change
rather than a blanket override. It does **not** change any transaction: a transfer to a
non-imported account is still booked in full on both legs (sum-to-zero leaves no choice); that
account simply ends up holding only the postings other files happened to mention.

**Opening balances.** Money exports an opening balance as a self-transfer (`L[Same Account]`), and
the target Hauptbuch account will usually already have one of its own. The importer proposes a
winner — **the earlier-dated one, breaking ties toward the non-zero one** — shows both, and lets
the owner override, including voiding Hauptbuch's. This is the *only* conflict raised when
importing into an account that already has postings; overlapping transactions are handled once, at
commit, by the duplicate scan (§9).

### 5.2 Categories

The key is the **full Money path** (`Audi:Fuel`). Money forbids duplicate category names, so the
path is a safe unique key. The target is **a Hauptbuch category *and* zero or more tags**:

> `Audi:Fuel` → category `Transportation › Car › Fuel` + tag `Cars:Audi`

This is the heart of the mapping work. Money's flat two-level taxonomy braids together two
dimensions that Hauptbuch deliberately keeps orthogonal: `Audi:Fuel`, `Audi:Insurance` and
`Audi:Repair` want *the same tag and different categories*, while `Food:Groceries` wants a category
built from *both* levels and no tag at all. ~300 paths collapse onto a much smaller curated tree —
the import is the one clean opportunity to consolidate 20 years of taxonomy drift.

The map targets the **semantic category node**, never a currency leaf: leaves are auto-provisioned
and hidden by `CurrencyLeafService`, and routing follows the paying account's currency
(`data-model` §6.5). The importer must not reach past that.

**Income vs expense**  every path is mapped by hand, and mapping onto an
*existing* category sidesteps the question entirely, since its type is already fixed.

### 5.3 Payees

Payees are **auto-created**, not mapped. A fourth map with several thousand rows would sink the
campaign, and the review reports only a **count** ("4,812 payees, 3,140 of them seen once").

Matching is case-insensitive, preferring the capitalised form where variants exist. Crucially the
importer **reuses `PayeeService.parseCreateNew`** — the existing `Name - City - Country` parser
that already splits on `-`/`,` and validates the last segment against `country_alias`
(`Germany`/`Deutschland`/`DEU`/`DE` all resolve) — plus `findByAddress(name, city, country)` for
the lookup. The owner has kept that pattern in Money since some point, so the structure is real
data, and there must not be a second parser for it.

Accepted cost, stated plainly: the payee picker gets much noisier, and this import is what makes
the FR-DM **payee merge** tool genuinely urgent (`implementation-plan.md` §3).

### 5.4 People

Money has no concept of a person; money lent to someone lives there as an ordinary account. Such an
account maps to a **Person**, which resolves to that person's `person_leaf` account for the relevant
currency (auto-provisioned by `PersonProvisioningService`, linked via `account_owner`, hidden from
account pickers and reached in the UI only through the `for`/`by` sigils).

From that point the importer treats it as an ordinary account id. A transfer `Cash → [Loan to Max]`
becomes `Cash −100 / Max-EUR +100` — byte-identical to what the `for` sigil produces — and a
categorised transaction *inside* that Money account becomes `Max-EUR −X / Food-EUR +X` like any
other account. **Personhood exists only in the map**; the transaction builder has no idea people
exist and needs no special case. This mirrors the engine, where settle-up is already just "a
transfer to a leaf id" via `DockCommitService`. 

One thing to mention is the currency: when importing a `Max` account, the owner must choose the 
currency for that account which determines the respective `person_leaf` account. Similarly, 
when importing a `Cash` account, the map of accounts is built for every transfer leg, therefore
the currency of the `Max` transfert target would determine the right leaf account for the `Max` person.
Since Money's account name is unique there will be a single mapping for the `Max` account,
and in case Max has multiple accounts in different currencies, this will be mapped explicitly.
This mechanism allows transfers between accounts of different currencies to be handled correctly.

---

## 6. Transfers and the mirror rule

A transfer appears **twice** — once in each account's export, from each side. The first sighting
creates the transaction; the second must be recognised and skipped.

### 6.1 The mirror signature is per-posting-pair

Matching is on **(date, both mapped account ids, absolute amount)** at the level of a **posting
pair**, not a transaction. This is forced by splits: a Money split line can itself be a transfer
(`S[Account B]`), and its mirror arrives weeks later in B's file as an ordinary *unsplit*
transaction. A transaction-level signature could never match those two, so the staging schema
indexes pairs (§11).

Matching happens **entirely inside staging**, against rows the importer itself produced. It can
therefore never swallow something the owner entered by hand — that risk is handled once, explicitly,
by the commit-time duplicate scan (§9), where the owner adjudicates rather than the machine guessing.

### 6.2 Cross-currency transfers always park

QIF carries no currency and no far-side amount. When A (EUR) transfers to B (CHF), A's file gives
`−100.00` and `[Account B]` and *nothing else* — but the engine's conditional sum-to-zero requires
both native amounts and `base_amount` on every leg. That number does not exist in the file.

So a cross-currency transfer is **parked** on first sighting and built only when the mirror supplies
the real far amount. One code path, no invented numbers, and — decisively — **no fabricated
`base_amount`**, which is a *frozen fact, never recomputed* (`data-model`), so a guess would be
wrong permanently.

An earlier design derived the far leg from `ExchangeRateService.rateAsOf` and landed it as
`pending_review`. It was dropped once parking had to exist anyway for the no-rate case: since all
100+ accounts are being imported, the mirror essentially always arrives, so the derive path was a
second code path serving a case that barely occurs. (`rateAsOf` is a carry-forward lookup that
returns empty when no rate exists *on or before* the date; rates are manual-entry only today, the
ECB feed being unbuilt, and the history reaches back to 2004 — so it would have returned nothing
for most of the campaign regardless.)

### 6.3 The import is a rate source

When the mirror arrives, both real native amounts are known — and **that pair *is* the actual
conversion rate for that date**. It is written back into `exchange_rate`, whose `source` check
constraint currently allows only `('ecb','manual')` and must be widened with a third value,
`'import'` (a migration).

The transaction is then built from the two real amounts, its `base_amount` frozen from that pair,
and it leaves the parked state.

### 6.4 Transfers whose far side never arrives

Clearing `expect-file` on the counterparty account (§5.1) resolves its pending mirrors: those
transfers are accepted exactly as the one file states them. For a **cross-currency** transfer to
such an account the far amount is still unknown and must be supplied by hand before the gate opens.

### 6.5 Open questions: to be clarified before the implementation

- Matching on absolute amount will not work for cross currency transfers, because the far
  side's amount is different. We should either losen the matching rules for cross-currency transfers
  and/or let the user match these manually.
- Transfers between accounts of different currencies neither of which is the base currency
  will not produce a currency exchange rate record. We should either ask the user to provide the 
  exchange rate for any of the currency for that date, or use the most recent rate available for
  any of these currencies.

---

## 7. Splits

Money writes a split as repeated `S` (category) / `E` (memo) / `$` (amount) triples. These map
directly onto Hauptbuch's split shape — one funding leg plus N category legs — with `E` landing on
`posting.note`. No new machinery is required; `posting_tag` already attaches per-posting
(`data-model` §10.2), so a split whose lines carry different tags needs nothing special.

Split lines that do not sum to the header total raise an **exception** and are never silently
adjusted.

The `L` line's role on a split transaction, and how Money exports a split containing a
**transfer-type leg**, are **open — see Q-IMP-1**.

---

## 8. Tags

Tags reach a transaction from the category map (§5.2) — and, if the owner used Money's **classes**
(the `Category/Class` slash suffix), from those too (**Q-IMP-3**).

Placement follows the rule the receipt path already established, and reuses its implementation
rather than restating it:

- map-derived tags land on the **category leg**;
- the **funding leg** carries the **intersection** across the category legs — for a single-category
  transaction that is simply the same tag set, and for a split it is what all lines share.

That is exactly `ReceiptSplitEntries.sharedTags` (receipts issue 20): intersect over the booked
lines, preserve first-occurrence order, yield nothing when any line is untagged;
`DockSplitService` then puts the resulting transaction-level set on the funding leg. The funding
leg must carry them because the register renders each row from *its own* posting's tags — leg-only
tags would otherwise be invisible there.

---

## 9. The review surface and the commit gate

The review is deliberately **about the maps and the exceptions, not about the rows**. One wrong
category mapping is 4,000 wrong transactions; one wrong transaction is one wrong transaction,
fixable in the register afterwards. Individual staged transactions stay browsable, never mandatory
to visit.

The review shows:

1. the **account map** (including people, `expect-file`, opening-balance conflicts),
2. the **category map** (category + tags, with sign evidence),
3. the **issues** list — unresolved mirrors, unresolved cross-currency parks, unmapped paths,
   unparseable lines, split-sum mismatches, destroyed-payee counts,
4. **per-account statistics** — transaction count, net sum, and date range — which is the
   verification device: it is ticked against Money's own balance for that account.

**The commit unlocks when:** no account is still marked `expect-file`; every Money category path is
mapped; zero unresolved cross-currency parks; and the ledger duplicate scan has been adjudicated.

**The ledger duplicate scan** runs once, at commit time, after mapping: staged transactions are
compared against the *live* ledger on **date + account + amount + category**, and every hit is
**presented for the owner to decide**. It is never a silent auto-skip — that is what makes it safe
despite Hauptbuch having been in daily use throughout the campaign.

**Open question**: when does the Review refresh? 
- On every new file being imported
- On every mapping created or edited
- On every exchange rate entry created or edited

But this does not cover the search for duplicates in the ledger, which is only run at commit time.
Should we implement a "refresh" button to allow the user to re-run the check for duplicates?
This might take long, should be run in the background, but what happens when the user enters a 
new transaction in the ledger while the duplicate check is running?
Should we lock the ledger while the duplicate check is running?

---

## 10. Commit

A **background worker** performs the commit inside **one atomic database transaction**, with the
page polling for progress (the pattern the receipt batch screen already established — not a second
one). A failure at row 39,000 leaves the ledger exactly as it was and staging fully intact for
another attempt. Staging is cleared only after the transaction succeeds.

**Every staged transaction is written through `LedgerService.recordTransaction`** — the identical
validated path every other write in the app uses, so sum-to-zero, conditional cross-currency
sum-to-zero, leaves-only and the base-currency gate are all enforced by construction. ~40k service
calls on a Pi is plausibly minutes, which is *why* it runs in a worker. A bulk-insert path with
after-the-fact SQL verification was rejected: it is a second write path into the ledger that
bypasses the domain service (CLAUDE.md §1.7), and every invariant added to `LedgerService` later
would have to be remembered here too. **If it proves too slow, optimise *inside* `LedgerService`
(JDBC batching), where every caller benefits — never by routing around it.**

---

## 11. Staging schema sketch (provisional)

Owned by the `importer` module. Naming follows CLAUDE.md §5 (surrogate `<entity>_id`; an FK reuses
the target's PK name). `import_*.transaction_id` FKs into `transaction` follow the precedent
`receipt.transaction_id` already set.

| Table | Holds |
|---|---|
| `import_session` | the single open campaign: state, charset & date-format defaults, `committed_at` |
| `import_file` | one uploaded export: filename, the Money account it is *for*, charset & date format actually used, counts |
| `import_account` | Money account name → target (`account_id` **or** `person_id`), `expect_file`, opening-balance resolution |
| `import_category` | Money path → semantic `account_id`, sign-profile counters, proposed type |
| `import_category_tag` | junction: a mapped path's tags (`tag_id`) |
| `import_transaction` | a staged transaction: date, payee text, note, reference no., cleared flag, opening-balance marker, state (`ready` / `parked` / `mirrored` / `excluded`) |
| `import_posting` | a staged leg: signed amount, note, unresolved Money category path or account name, and the **mirror pair** link (§6.1) |

---

## 12. Module placement and testing

Everything lives in **`importer`**, including its own controller — feature screens' controllers
belong to their feature module, not `web` (CLAUDE.md §3). It consumes only the **public top-level
types** of `ledger` (`LedgerService`, `PayeeService`, `ExchangeRateService`), `accounts`,
`categories` and `debts`. `importer` is a leaf consumer, so no cycle is introduced —
`ApplicationModules.verify()` is the arbiter.

| Tier | Covers |
|---|---|
| `test` (unit) | **the whole parser** — canonical records from fixture text: dates (`D26/11'2011`, ambiguity), charset fallback, splits, transfers, opening balances, `!Type:Invst` rejection, `?`-payee classification. Pure, no Spring, no DB. Also the map-resolution and mirror-signature logic with repositories mocked. |
| `integrationTest` | staging repository round-trips; the upload → preview → stage → map → gate flow as MockMvc/htmx acceptance; the commit flow end to end against real Postgres |
| `sqlLogicTest` | mirror matching, the per-account count/sum statistics, and the ledger duplicate scan — grouping and multi-table queries whose logic lives in the SQL |

---

## 13. Slicing

| Slice | Delivers |
|---|---|
| **a** | The QIF parser → canonical representation (§3, §4). Charset detect + override, date detect + confirm, splits, transfers, opening-balance rows, account-type proposal, `!Type:Invst` rejection, `?`-payee classification. Unit tier only. |
| **b** | Session + staging schema (migration), upload → parse → preview → stage. One open session at a time. |
| **e′** | **Per-account statistics only**, pulled forward — see below. |
| **c** | Account map (§5.1, §5.4): mandatory, many-to-one, existing / new / person, `expect-file`, opening-balance reconciliation. |
| **d** | Category map (§5.2) with tags, sign evidence, bulk editing; payee auto-create (§5.3). |
| **e** | Mirror matching (§6.1), cross-currency parking (§6.2), rate write-back + the `exchange_rate.source` migration (§6.3), issues list. |
| **f** | Commit (§10): background worker, atomic transaction, ledger duplicate scan, clear staging. |

**Why the statistics move ahead of the maps.** The riskiest unknown in the whole feature is "does
the parser read my 20-year-old data correctly?" — and it can be answered with **zero ledger risk**
the moment staging exists: upload a real 60k-line export and read back *"12,418 transactions, net
−43,204.17, 2004-07-01 → 2026-08-30"*, then tick it against Money's own balance. Answering that
before building two map UIs on top of the parser is worth the small reordering.

---

## 14. Open questions

| # | Question |
|---|---|
| **Q-IMP-1** | **`L` on a split transaction**, and how Money exports a split containing a **transfer-type leg**. The owner has such splits; the export shape is not yet known. To be settled against a real sample at slice a. |
| **Q-IMP-2** | **`transaction.lifecycle` for imported transactions.** Assumed `confirmed` throughout; deliberately deferred for a separate discussion. |
| **Q-IMP-3** | **Money classes** (`Category/Class`). If used, they are a second tag source alongside the category map (§8); if not, the parser needs only a guard. |
| **Q-IMP-4** | **Cross-currency transfers**: how to handle transfers when neigher of the currencies is the base currency. The importer cannot invent a rate; the owner must supply it. To be settled against a real sample at slice e. |
| **Q-IMP-5** | **Ledger duplicate scan**: when to refresh the review surface, and how to handle a new ledger entry while the scan is running. To be settled against a real sample at slice f. |

A **sample QIF export** is to be supplied and the §4 dialect assumptions verified against it at
slice a.

---

## Changelog

- **v0.1 (2026-08-30):** Initial design, from a full grilling pass. Settles **Q9** (format = QIF).
  Establishes the session/staging model, the three maps (accounts incl. people, categories →
  category + tags, payees), the per-posting-pair mirror rule, always-park cross-currency transfers
  with rate write-back, the maps-and-exceptions review with per-account statistics, the commit gate,
  and commit through `LedgerService` in one atomic transaction. Q-IMP-1/2/3 left open.
