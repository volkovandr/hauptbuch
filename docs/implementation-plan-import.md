# Hauptbuch — Import Plan: the Money migration (QIF)

**Status:** Draft v0.8
**Date:** 2026-09-01
**Owner:** volkovandr
**Parent:** `implementation-plan.md` §3, first bullet (the item currently being built). This doc is
the detail the sub-plan pattern pushes out of the main plan — deleted on completion with a summary
folded back, like the stage-7 and stage-9 sub-plans.
**Authoritative design:** `docs/import.md` (v0.1). That doc owns every session, mapping, mirror,
gate and dialect rule; section references below (§2–§14) point there unless prefixed otherwise.
This doc only sequences the build.

> `import.md` §13 slices the feature into **a–f (plus e′)**. Those slices are the right *shape* but
> not the right *size* — each is several days. This plan cuts them into **16 steps, each one short
> coding session**, each ending green on `./gradlew check` and demoable or verifiable on its own.
> The slice letters are kept verbatim so the two docs never drift apart.

---

## 0. Sequencing rationale

Three constraints set the order, and they agree with each other:

1. **The parser is the only piece with no dependencies at all** (§3: pure Java, no Spring, no DB).
   It is also where a 20-year-old dialect can surprise us. It goes first, entirely in the unit tier,
   against a real sample export.
2. **The riskiest question — "does it read my data correctly?" — is answered before any map UI is
   built** (§13). Staging plus per-account statistics (steps b1–b3, e′) is the cheapest possible
   answer: upload a real export, read back count / net sum / date range, tick it against Money's own
   balance. Zero ledger risk, because nothing writes to the ledger until step f2.
3. **Everything downstream of the maps needs the maps.** Mirror matching keys on *mapped account
   ids* (§6.1), so it cannot precede the account map; the commit needs both maps and the mirror
   resolution. Hence a → b → e′ → c → d → e → f, exactly the §13 order.

**Nothing in steps a–e writes a single row to `transaction` or `posting`.** The ledger is touched
once, in f2, through `LedgerService.recordTransaction` (§10). That is what makes every step before
it safe to ship into a book that stays in daily use.

---

## 1. Cross-cutting decisions to settle before/while slicing

- **Module edge to `operations` — needs an explicit decision.** `import.md` §12 says `importer`
  consumes the public types of `ledger`, `accounts`, `categories` and `debts`. But the category map
  targets a **semantic** node and routing to the currency leaf goes through `CurrencyLeafService`,
  which lives in **`operations`**, not `categories`. So the real edge set is
  `importer → {ledger, accounts, categories, debts, operations}`. That is the same edge stage 7b and
  stage 9g already took (dock/receipt commit paths live in `operations`), it is acyclic, and
  `ApplicationModules.verify()` is the arbiter — but `import.md` §12 should be corrected to say so
  (step a1).
- **The importer builds its own `TransactionDraft`s and calls `LedgerService` directly** (§10),
  rather than going through `DockCommitService`. The dock services exist to resolve *typed UI input*
  (payee text, ghost categories, sign-free amounts) — the importer has already done its own
  resolution in staging, and the dock's shape does not fit a 40k-row batch. `LedgerService` is the
  invariant boundary, and it is the one both callers share.
- **Migrations are step-local** (the stage-7/9 precedent): **V19** = the staging schema (b1),
  **V20** = widening `exchange_rate.source` with `'import'` (e3). Next free number is V19.
- **Everything lives in `importer`, including its controller** (§12, CLAUDE.md §3). Its screens
  hang off `/import`.
- **Open questions and where each is settled:** ~~**Q-IMP-1** (`L` on a split; a split containing a
  transfer leg) and **Q-IMP-3** (Money classes)~~ — **settled at a1**, against the sample (see a1
  below and `import.md` §7/§8). **Q-IMP-2** (`lifecycle` for imported rows; assumed `confirmed`)
  before **f2**. **§6.5 / Q-IMP-4** (cross-currency mirror signature; a rate when neither side is
  base) at **e2/e3**. **Q-IMP-5** (when the review refreshes; the duplicate scan racing live
  entry) at **f1**.

---

## Slice a — the QIF parser (unit tier only)

Pure Java, no Spring, no DB, no ids resolved (§3). Output is the canonical representation, which is
also the seam FR-IMP-05's CSV importer plugs into later.

### a1 — Sample verification & dialect settlement ✅ **complete** (owner-confirmed 2026-08-31)
Held three real Money exports (a credit card, and two current accounts sharing a currency pair,
one the other's transfer counterparty) against §4. Confirmed the header set, the `D26/11'2011`
date shape with unambiguous DD/MM evidence, the `?` substitution (full and partial payee
destruction, and a genuine cp1252-only byte pair), `!Type:CCard`/`!Type:Bank` (no `Invst` in this
sample), and the `S`/`$` split shape (no `E` memo in this sample, still supported per spec).
Settled **Q-IMP-1** (`L` repeats the first split line; a split leg may be `[Account]`) and
**Q-IMP-3** (classes are used; second tag source, same `?`-handling as payees) — both folded into
`import.md` §7/§8. Corrected `import.md` §12 with the `operations` edge. Two findings not
anticipated by the design doc, folded into `import.md` §4.2 and §4.5: amounts carry a
thousands-grouping comma; a destroyed **account** name now **rejects the whole file**
(owner-confirmed 2026-08-31) — production exports are not expected to hit this, so the answer is
refuse and re-export rather than a mapping-time special case, the same treatment as `!Type:Invst`.
Full findings and the settled resolutions are in `import.md` §14's v0.2 entry — not repeated here
(§8a). The three source files are **not committed** (personal data — real name, real merchants);
they stay local to the owner's machine, gitignored under `qif/`.

**Not exercised by this sample**, so still open for a future step: the `Name - City - Country`
payee-address convention (d2), `C` values other than `X` (a2), an `!Type:Invst` account (a2) and a
destroyed-account-name file (a4) to prove both rejection messages end to end.

**Fixture authoring deferred to a2–a4.** This codebase's convention (see `ToonReceiptDecoderTest`)
is inline Java text blocks, not classpath resource files — a2–a4's own tests will carry sanitized,
synthetic excerpts reproducing each shape this step found (the comma-grouped amount, the DD/MM
date, the cp1252 byte pair, the full/partial `?`-payee, the transfer-leg split, the class suffix),
authored fresh rather than copied from the real files.

### a2 — Canonical records + the record reader ✅ **complete** (owner-confirmed 2026-08-31)
`ImportedTransaction` / `ImportedLine` / the line target (category path **or** account reference) as
records; the `^`-terminated record reader; the `!Type:` header → asset/liability **proposal**;
`!Type:Invst` **rejected** by name (§4.5); fields `D` `T`/`U` `P` `M` `N` `C` `L`, `A` ignored.
Simple one-line transactions only. Takes an already-decoded string — decoding is a3. `memo` (`M`)
and `referenceNumber` (`N`) are kept as **separate** canonical fields, matching §3's shape exactly —
the "prefixed into `transaction.note`" combination (§4.2) is a ledger-write-time concern for a
later stage, not something a2 bakes in.

**Done when:** unit tests build canonical records from the fixture text, including the `Invst`
rejection message and the separate `M`/`N` fields. **25 unit tests, all green**; `checkstyleMain`,
`pmdMain`, `spotbugsMain` and their `*Test` variants clean.

### a3 — Charset & date-format detection with evidence ✅ **complete** (owner-confirmed 2026-09-01)
Strict UTF-8 decode probe with **windows-1252 fallback** (§4.4), and the first ~50 decoded lines
carried out for the preview. Whole-file date detection (§4.3): first component >12 ⇒ `DD/MM`,
second >12 ⇒ `MM/DD`, neither ever ⇒ **ambiguous** — each carrying its **evidence** (the proving
line and its number). Both are *proposals* with an override, never a silent choice. `QifCharset`
also strips a leading UTF-8 BOM (Money writes none, but a re-encoded file carries one and the
record reader reads the first character literally); a file whose own dates prove *both* orders, or
a `D` line that is not a date, is refused rather than guessed at.

**Done when:** the unit tier proves all three date outcomes with their evidence strings, the cp1252
fallback on a byte sequence invalid as UTF-8, and that ASCII-only input is decoded identically
either way. **13 unit tests, all green**; `checkstyleMain/Test`, `pmdMain/Test`, `spotbugsMain`
clean. Not folded into `QifParser` — that wiring is b2. Class-suffix `L` values (`Category/Class`,
`[Account]/Class`) still land as a raw `CategoryPath`; splitting them into category + tag is a4.

### a4 — Splits, transfers, opening balances, destroyed payees & accounts ✅ **complete** (owner-confirmed 2026-09-01)
The `S`/`E`/`$` triples → multiple `ImportedLine`s with `E` on the line memo; a **split-sum
mismatch raises**, never adjusts (§7). `L[Account]` → an account-reference target; the
self-transfer `L[Same Account]` → the **opening-balance marker** (§5.1). `?`-payee classification
(§4.4): a name that is *entirely* `?`/whitespace yields **no payee**; a partially destroyed name is
kept **verbatim**. The parse result also carries the **set of account names the file mentions**
(the account being imported plus every transfer counterparty) — the input to the account map, and
where the destroyed-account-name check (§4.5) runs: any name in that set that is entirely
`?`/whitespace **rejects the whole file**, the same way `!Type:Invst` does.

**Done when:** the unit tier covers each shape from fixtures, including a split containing a
transfer leg in whatever form a1 established, the split-sum exception, and the
destroyed-account-name rejection with its message.

---

## Slice b — session, staging schema, upload → preview → stage

### b1 — V19 staging schema + session lifecycle ✅ **complete** (owner-confirmed 2026-09-01)
The seven tables of §11 (`import_session`, `import_file`, `import_account`, `import_category`,
`import_category_tag`, `import_transaction`, `import_posting`), named per CLAUDE.md §5. The session
service enforcing **one open session at a time** (§2) — the rule that makes the mirror rule and the
gate definable — plus discard-session, which is the only "undo" the feature has.

**Done when:** the migration applies on a fresh container; the session repository round-trips in the
integration tier; the unit tier proves a second open session is refused.

### b2 — Upload → preview (nothing staged yet) ✅ **complete** (owner-confirmed 2026-09-01)
The `/import` screen: choose a file, **state which Money account it is for** (the file does not say
— §4.1), get back the preview — proposed account type from the header, detected charset, detected
date format **with its evidence** or a loud AMBIGUOUS, the first ~50 decoded lines, the record
count, and the `Invst` rejection where it applies. The owner **confirms or overrides** charset and
date format here (§4.3: a day/month swap corrupts the whole campaign and surfaces years later, if
ever — so it is confirmed, never assumed). **The filename carries no identity** (§2): if it
matches one already uploaded this session, the preview asks **replacement or coincidence** rather
than assuming either — Money reuses one filename across every export, so a match is expected, not
suspicious.

**Done when:** MockMvc acceptance drives upload → preview → override for a fixture file, and
uploading a second file under the same name prompts the replacement-or-coincidence choice instead
of silently doing either; nothing has been written to any staging table.

### b3 — Stage the confirmed file *(implemented; awaiting owner confirmation)*
Confirming the preview writes `import_file` + `import_transaction` + `import_posting` (targets kept
as the **unresolved** Money strings, §11), and folds the file's account names into `import_account`
and its category paths into `import_category` as unmapped rows — accumulating across files (§5).
The files list with per-file counts, and **remove a staged file** so a mis-stated account or a wrong
date format is recoverable without discarding the campaign. A **replacement** chosen at b2 is this
same removal followed by staging the new file under its old slot — no separate mechanism.

**Done when:** staging a fixture file twice-over accumulates map rows without duplicating them,
removing a file removes exactly its rows, a replacement leaves only the new file's rows behind, and
the repository round-trips are green.

---

## Slice e′ — per-account statistics (pulled ahead of the maps)

**e′ — the verification device.** Per staged Money account: **transaction count, net sum, date
range** (§9.4) — the number that gets ticked against Money's own balance for that account. The
review page is born here as a skeleton; c, d and e hang their panels off it.

Grouping over two staging tables ⇒ **`sqlLogicTest`, written first** (CLAUDE.md §6).

**Done when:** a real 60k-line export stages and reads back its statistics, and the owner has ticked
at least one account against Money. *This is the step that retires the feature's biggest risk — do
not start slice c before it passes.*

---

## Slice c — the account map (§5.1, §5.4)

### c1 — Map to an existing or a new account
Per Money account name: target an **existing** Hauptbuch account, or a **new** one (type proposed
from the header, **currency asked** — QIF carries none, so it can only be asked). **Many-to-one** is
allowed and load-bearing: it is both the merge facility and the entire junk-account story (§5.1).
Mapping is mandatory before the gate opens.

**Done when:** the map screen resolves both targets, several Money accounts can share one target,
and the unit tier covers the resolution with repositories mocked.

### c2 — Person targets and `expect-file`
A Money account may map to a **Person** (§5.4), resolving through `PersonProvisioningService`
`ensureLeaf(person, currency)` to that person's per-currency leaf — after which the importer treats
it as an ordinary account id and the transaction builder never learns people exist. The currency is
chosen per mapped account, which is exactly what makes a cross-currency transfer to a person land on
the right leaf. Plus the **`expect-file`** flag per Money account — "am I still waiting for this
account's own export?" — the gate's only escape hatch, and per-account, recorded and visible rather
than a blanket override.

**Done when:** a person-mapped account produces the same leg a `for`-sigil entry would; clearing
`expect-file` is persisted and shown on the review.

### c3 — Opening-balance reconciliation
Money's opening balance is a self-transfer (§5.1) and the target account usually already has one.
Propose a winner — **the earlier-dated one, ties broken toward the non-zero one** — show both, let
the owner override, including voiding Hauptbuch's own. The *only* conflict raised at map time;
overlapping ordinary transactions are handled once, at commit, by the duplicate scan.

**Done when:** each of the three outcomes (keep Hauptbuch's, take Money's, override) is recorded on
`import_account` and asserted; the unit tier covers the proposal rule including the tie.

---

## Slice d — the category map (§5.2) and payees (§5.3)

### d1 — Path → category + tags
The heart of the mapping work: `Audi:Fuel` → category `Transportation › Car › Fuel` **+ tag
`Cars:Audi`**, keyed on the full Money path. ~300 paths onto a much smaller curated tree, so the
screen needs **bulk assignment** (map many selected paths to one category, or one tag across a
selection) or the campaign stalls. Sign evidence per path (how many staged lines are +/−) is shown
to make income-vs-expense obvious. The map targets the **semantic node, never a currency leaf** —
routing stays with `CurrencyLeafService` and the paying account's currency.

**Done when:** a path maps to category + N tags, bulk assignment covers a multi-select, the counts
come from a `sqlLogicTest`-first query, and the screen refuses to offer currency leaves.

### d2 — Payee resolution and counts
Payees are **auto-created, not mapped** (§5.3): resolution is a routine, reusing
`PayeeService.parseCreateNew` (the existing `Name - City - Country` parser and its `country_alias`
validation) and the address lookup — **there must not be a second parser**. Matching is
case-insensitive, preferring the capitalised variant. The review reports only **counts**: distinct
payees, how many seen once, and how many rows carry a **fully destroyed** name and will therefore
book with `payee_id` null.

**Done when:** the counts query is green in the SQL tier, the resolution routine is unit-tested
against the existing parser (not a copy of it), and it is the same routine f2 will call.

---

## Slice e — mirrors, cross-currency parks, the issues list

### e1 — Mirror matching within staging
The **posting-pair** signature — (date, both mapped account ids, absolute amount) — never a
transaction-level one, because a split line can itself be a transfer whose mirror arrives weeks
later as an ordinary unsplit transaction (§6.1). Matching runs **entirely inside staging**, so it
can never swallow something the owner typed. Re-runnable, because the account map keeps changing
under it.

Multi-table grouping ⇒ **`sqlLogicTest`, written first**, with the split-leg case crafted explicitly.
**Done when:** the second sighting is marked `mirrored` and excluded from the commit, re-running
after a map edit is idempotent, and the split-leg mirror matches.

### e2 — Cross-currency parking, and matching them by hand
A cross-currency transfer **parks** on first sighting (§6.2): QIF carries no far-side amount, and
`base_amount` is a frozen fact that must never be invented. Settle **§6.5**: the absolute-amount
signature cannot match a cross-currency pair, so the automatic signature is **loosened for that case**
(date + both mapped account ids, near-side amount only) and any residual ambiguity is resolved by an
explicit **manual match** in the issues list. Also: a parked transfer to an account whose
`expect-file` was cleared needs its far amount **entered by hand** (§6.4).

**Done when:** a cross-currency pair from two fixture files parks then resolves; a manual match and a
hand-entered far amount both close a park; nothing books a derived or guessed amount.

### e3 — Rate write-back (V20) and the non-base pair
When a mirror supplies both real native amounts, that pair **is** the conversion rate for that date
(§6.3): widen `exchange_rate.source` to allow `'import'` (**V20**) and write it back; the
transaction's `base_amount` is frozen from the same pair. Settle **Q-IMP-4** for a pair where
*neither* currency is the base one — the importer cannot invent the missing rate, so the owner
supplies it (or the existing carry-forward `rateAsOf` covers it, if it can).

**Done when:** the migration applies, a resolved cross-currency mirror leaves a rate row with
`source = 'import'`, and the non-base case has a settled, tested answer.

### e4 — The issues list
The review's third panel (§9.3): unresolved mirrors, unresolved parks, unmapped paths, unparseable
lines, split-sum mismatches, destroyed-payee counts — each linking to where it is fixed. Plus the
**gate state**: no account still `expect-file`, every path mapped, zero unresolved parks (the
duplicate scan is f1's half of the gate).

**Done when:** each issue class appears from crafted staging data and disappears when resolved; the
gate reports itself locked with a reason and unlocks when all three conditions hold.

---

## Slice f — the commit

### f1 — The ledger duplicate scan
Staged rows compared against the **live** ledger on date + account + amount + category (§9), every
hit **presented for the owner to adjudicate** — never a silent auto-skip, which is what makes it
safe after weeks of daily use. Settle **Q-IMP-5** here: when the scan re-runs, and what happens if
the owner books a transaction while it is running. Recommended answer to record and test: the scan
is a **re-runnable snapshot** with its own timestamp, re-run from a button, and a stale adjudication
is re-raised rather than trusted — **no ledger lock**.

Multi-table grouping across staging *and* ledger ⇒ **`sqlLogicTest`, written first**.
**Done when:** crafted overlaps are found and adjudicated both ways, and a decision made against a
stale snapshot is re-raised, not silently applied.

### f2 — Commit: background worker, one atomic transaction
Every staged transaction written through **`LedgerService.recordTransaction`** — the same validated
path as every other write, so sum-to-zero, conditional cross-currency sum-to-zero, leaves-only and
the base-currency gate hold by construction (§10). A **background worker** inside **one** database
transaction, with the page polling for progress — the `ReceiptBatchAnalyser` pattern, not a second
one. A failure at row 39,000 leaves the ledger untouched and staging intact. Staging is cleared only
after success. Settle **Q-IMP-2** (`lifecycle`; assumed `confirmed`) before writing this. The
**backup → commit → backup** ceremony (§2) is presented on the screen, using the existing
`BackupService`.

**If it is too slow on the Pi, optimise *inside* `LedgerService` (JDBC batching) where every caller
benefits — never by routing around it.**

**Done when:** a multi-file fixture campaign commits end to end against real Postgres in the
integration tier; a forced mid-run failure leaves zero ledger rows and full staging; the balances of
the committed accounts match the e′ statistics.

---

## 2. Working assumptions in this plan

| # | Assumption | Overturn impact |
|---|---|---|
| I-1 | ~~A real sample export is available at a1; the §4 dialect holds~~ — **realised 2026-08-31**, with two dialect corrections folded in (§4.2 amount commas, §4.5 destroyed-account rejection) | n/a — resolved |
| I-2 | `importer → operations` is an accepted module edge (`CurrencyLeafService`) | Otherwise the leaf-routing API moves to `categories` — a small refactor, plus an `import.md` §12 correction |
| I-3 | The importer builds `TransactionDraft`s directly, not via `DockCommitService` | Re-shapes f2 only |
| I-4 | Imported rows are `confirmed` (Q-IMP-2) | A column default and a filter; cheap while it stays undecided, cheap to change at f2 |
| I-5 | Mirror re-matching is cheap enough to re-run on every map edit | If not, it becomes an explicit "re-match" button — an e1 UI change, not a schema one |

---

## Changelog

- **v0.8 (2026-09-01):** **b3 implemented** (awaiting owner confirmation) — Confirm & stage writes
  `import_file` / `import_transaction` / `import_posting`, folds referenced account names and
  category paths into `import_account` / `import_category` as unmapped rows (idempotent on the
  session-unique keys, so re-staging never duplicates), the campaign screen grew a **staged-files
  list** with per-file counts and a **remove-staged-file** action, and the b2 filename-clash check
  now also spans staged `import_file` rows (a "replace" drops the staged rows, then the new file
  stages under its old slot). **One decision to ratify:** `import_posting.amount` is stored in
  **Hauptbuch's sign convention, not Money's** — the funding leg on the file's own account carries
  the record total `T` verbatim (an asset outflow is negative in both conventions) and every
  category/transfer leg carries the **negation** of its Money `$`/`T`, so a staged transaction's
  legs sum to zero by construction and f2 hands them to `LedgerService` without a sign flip. The
  design doc did not pin this; §7/§10 are candidates to record it. Incidental: `ImportedTransaction`
  gained a `payeeDestroyed` flag (b1 reserved the column, the parser had collapsed destroyed and
  absent payees); `QifDateFormat.toLocalDate` added (parses `D26/11'2011` with the confirmed
  order, Money's two-digit-year separator convention, refuses non-dates); repositories take the
  row record (`insert(record)`, the `AccountRepository` idiom); `import_file.filename` is read as
  `source_filename` to match `PendingImportUpload`. A still-ambiguous date order blocks staging.
  Next: e′ (per-account statistics).
- **v0.7 (2026-09-01):** **b2 complete** — `/import` screen (upload → preview, nothing staged).
  Mechanism notes: (1) between upload and b3's
  Confirm the file is held in the **HTTP session** (`ImportUploadSession`, bytes Base64) and the
  preview recomputed per render — there is no staging row to unwind on discard; (2) the
  filename-identity clash check (§2) runs against the **session-held pending uploads** for now — b3
  extends it to staged `import_file` rows; (3) a **pending-uploads list + drop-a-pending-upload**
  action were added (not in §13) as the surface the clash flow needs — distinct from b3's
  "remove a staged file". Scope additions outside `importer`: an **Import** entry in the nav shell
  (`web/NavItem`), and `jakarta.servlet-api` on the `integrationTest` suite (a MockMvc test reads a
  redirect `Location`). Next: b3 (stage the confirmed file).
- **v0.6 (2026-09-01):** **b1 complete** — V19 lands the full seven-table staging schema; the
  session lifecycle (`ImportSessionService`: one open session at a time, backed by a partial unique
  index; discard as the only "undo"). Scope: two columns added beyond the §11 sketch because only
  V19/V20 are planned — `import_account.target_currency_code` (c1/c2 pick a currency for a new
  account or a person leaf) and `import_transaction.payee_destroyed` (§5.3 counts entirely-`?`
  payees separately from absent ones). `ImportSessionState` follows the `ReceiptState` pattern.
  Next: b2 (upload → preview).
- **v0.5 (2026-09-01):** **a4 complete** — splits (`S`/`E`/`$` → one `ImportedLine` per leg,
  split-sum mismatch raises), the opening-balance self-transfer marker, `?`-destroyed payee
  classification, and `ImportedFile.referencedAccountNames` with the destroyed-account-name file
  rejection. `QifParser.parse` now takes the stated Money account name (the file does not carry it,
  §4.1) — the seam b2 fills. The `/Class` suffix is split off the path onto `ImportedLine.className`
  (the a3 changelog assigned this to a4), resolved by the new package-private `QifTarget`; `QifText`
  holds the shared `?`-destruction predicate. 46 importer unit tests; `./gradlew check` green. No
  scope change. Next: b1 (V19 staging schema + session lifecycle).
- **v0.4 (2026-09-01):** **a3 complete** — `QifCharset` (strict UTF-8 probe → windows-1252
  fallback, leading-BOM strip, `previewLines()`) and `QifDateFormat` (whole-file day/month-order
  detection carrying the verbatim proving line and its number; contradictory-order files and
  non-date `D` lines refused), both pure Java in `importer`, unit-tier only. 13 tests;
  `checkstyle`/`pmd`/`spotbugs` clean. No scope change. A code-review finding folded in: the BOM
  strip belongs in the decoder because the record reader reads the first character literally. Next:
  a4 (splits, transfers, opening balances, destroyed payees & accounts).
- **v0.3 (2026-08-31):** **a2 complete** — the canonical records (`ImportedTransaction`/
  `ImportedLine`/`ImportedTarget`), the `^`-terminated record reader, the `!Type:` header proposal
  (with the `Invst` rejection), and simple one-line field parsing, all pure Java in `importer`. A
  split (`S`/`E`/`$`) encountered before a4 lands is refused rather than silently dropped. `memo`
  and `referenceNumber` kept as separate fields (§3), correcting this doc's earlier "N-prefixed
  note" wording. 25 unit tests; `checkstyleMain/Test`, `pmdMain/Test`, `spotbugsMain` clean. Next:
  a3 (charset & date-format detection).
- **v0.2 (2026-08-31):** **a1 complete** — a real three-file Money export verified against §4,
  settling Q-IMP-1 and Q-IMP-3 (folded into `import.md` v0.2). Two dialect corrections the design
  doc missed: amounts carry a thousands-grouping comma; a destroyed *account* name **rejects the
  whole file** (owner-confirmed) — production exports are not expected to hit this, so refuse and
  re-export rather than build mapping-time disambiguation for it. The sample files are personal
  data and are not committed. Owner clarification folded into b2/b3: **the filename carries no
  identity** (Money reuses one filename across every export) — a same-name re-upload within a
  session asks replacement-or-coincidence, reusing b3's existing file-removal mechanism rather
  than a new one. Next: a2 (canonical records + record reader).
- **v0.1 (2026-08-31):** Initial sequencing of `import.md` §13 into 16 session-sized steps. No scope
  change to the feature. Records two things `import.md` does not: the `importer → operations` edge
  needed for `CurrencyLeafService` (§1), and the recommended answers to §6.5 and Q-IMP-5, both still
  open for the owner to settle at their step.
