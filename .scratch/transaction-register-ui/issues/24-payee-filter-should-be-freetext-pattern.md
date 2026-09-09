# Register payee filter should be a free-text pattern field, not a single-select dropdown

Status: needs-triage
Category: enhancement
Severity: low
Area: Transaction register — filters (`register.html` payee `<select>`, `RegisterFilter`, `RegisterService`/`RegisterRepository`)

## The problem

The register's Payee filter (`register.html` lines 141–146) is a `<select name="payeeId">` — one
option per payee, populated from `register.payees()` (`RegisterService.payeeOptions`), so it can
match **exactly one** payee at a time.

The same real-world merchant often exists as several `payee` rows because receipts and statements
spell it differently. The owner has already hit **"TEDi"** and **"TEDi GmbH"** as two separate
payees; to see both merchants' rows there is no single filter value that works — you have to look
at each in turn.

## What the owner wants

Replace the dropdown with a **free-text field that accepts a regular expression** and filters rows
to payees whose name matches. `TEDi` (or `^TEDi`) would then match both `TEDi` and `TEDi GmbH` in
one view.

## Notes / decisions for triage

- **Regex vs. substring.** The stated need ("TEDi" catches "TEDi GmbH") is met by a plain
  case-insensitive **substring** match, which has no failure mode. A full regex (`name ~* :pattern`)
  is what was asked for and is more powerful, but a malformed pattern makes Postgres raise
  `invalid regular expression` — that must surface as a friendly "not a valid pattern" message on
  the filter, never the htmx error boundary (cf. transaction-register-ui/10). Decide: substring
  only, or regex with validation. A middle option: substring by default, treat the field as a regex
  only when it contains regex metacharacters.
- **What it matches against.** Match the bare `payee.name`. The register's *display* label is the
  composed `Name · City · Country` (register §3.4, to disambiguate same-named payees) — matching
  the composed string would be surprising. A name pattern deliberately *unions* same-named payees in
  different cities; that is the point.
- **Keep it a plain GET form, no JS** (§1.6). A `<datalist>` of existing payee names on the input is
  a nice-to-have (type-ahead while still free-text) and stays within the leaf-free rule.
- **Wire/plumbing change.** The filter param stops being `Long payeeId`. This ripples:
  - `RegisterFilter.payeeId` → a `String` pattern; `RegisterController` `@RequestParam` +
    `filterFrom`; `RegisterService` (drops `payeeOptions` / the selected-flag wiring, keeps the raw
    text to echo back into the field).
  - `RegisterRepository`: the one line `and (cast(:payeeId as bigint) is null or threaded.payee_id
    = :payeeId)` (outer `where`, line ~122) becomes a name-pattern predicate — e.g. `and
    (cast(:payeePattern as text) is null or exists (select 1 from payee px where px.payee_id =
    threaded.payee_id and px.name ilike '%'||:payeePattern||'%'))`. Running balance is computed in
    the `threaded` CTE *before* this predicate, so as-of balance semantics are unchanged (the query
    comment at lines 47–48 already relies on that for the current payee filter).
  - The active-filter hidden field `viewPayeeId` is carried through the entry dock and split panel
    on every re-render — `entry-dock.html` (~75, ~350), `split-panel.html` (~57), and the
    `viewPayeeId` field on `DockEntryForm` / `SplitForm` / `DockEntry` / `SplitEntry` and their
    binders (`SplitFormBinder`, `SplitLineArrays`, `RegisterEntryController`,
    `RegisterSplitController`). Rename the field and change its type in step or a commit re-render
    drops the filter.
  - Check the receipt `?selected=` register jump (`RegisterController` line ~79, `jump`) — it
    builds its own filter and almost certainly sets no payee, but confirm.
- **Docs.** `docs/ui-transaction-register.md` §2.3 already calls the payee filter a "Free filter" —
  update that row and the surrounding table to describe the pattern field. There is an open design
  note **Q-UI-3** nearby about payee matching (unrelated — that one is about the ghost-category
  mode) — don't conflate.
- **Tests.** The register query is already SQL-resident (recursive CTE + window function) with a
  `sqlLogicTest`; add cases for the pattern predicate (two same-prefixed payees matched by one
  term, a non-matching term, an empty/blank term = all). Filter round-trip and the field echo go in
  the `integrationTest` controller/htmx acceptance.

## Comments

Filed 2026-09-08 from the owner: the payee dropdown can only pick one payee, but one merchant is
often several payee rows ("TEDi" vs "TEDi GmbH"); wants a free-text regex filter so both show at
once.
