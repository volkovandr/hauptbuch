# Register list keeps showing a blank Merchant even after the receipt has a proper Payee

Status: ready-for-agent
Category: bug
Severity: medium
Area: Receipts — register list (`receipts.html`) × post-process editor payee assignment
(`ReceiptEditorService`, plan §9f)

## Symptom (owner report)

The AI couldn't detect the merchant *name* on a receipt, but did detect the city and country. In
the register list, Merchant showed empty. Opening the receipt, the owner was able to type in the
merchant name properly in the editor and Save. Back on the list, Merchant was **still** empty. The
owner's expectation: once a proper Payee has been assigned to the receipt, the list should read the
name from there instead of from the (still-empty) original AI parse.

## What exists today

- `receipts.html:146`: `<td class="receipts__merchant" th:text="${r.merchantText()}"></td>` — the
  list column reads `Receipt.merchantText()` directly and only that.
- `Receipt.java:30,48-49`: `merchantText` is documented as "parsed merchant (9e); null until
  processed" and explicitly **stays the raw parse fact forever** — the javadoc on `payeeId` says so
  outright: "null until then (`merchantText` stays the parse fact)". Saving a payee in the editor is
  by design never written back into `merchantText`.
- `ReceiptEditorService.java:135-138`: Save resolves the operator's typed payee text through
  `PayeeService.resolvePayee(...)` (find-or-create) and stores the result as `Receipt.payeeId` — a
  real, separate field, already populated correctly per the owner's repro.
- `ReceiptRepository.findForRegister` (`ReceiptRepository.java:65-76`, the query backing the list)
  is a plain `select * from receipt …` with **no join to `payee`** — so even if the template wanted
  to show the assigned payee's name, the list query doesn't fetch it today.

So the gap is real and two-layered: the template only ever reads `merchantText`, and the query
underneath it doesn't carry payee data to read from even if the template changed.

## A related, smaller gap in the same cell

Independent of `payeeId`: `Receipt` already has a `merchantDisplay()` helper (`Receipt.java:94-105`)
that composes `name - city - country`, dropping blank parts — built specifically for "the AI got
some merchant parts but not others" (its javadoc cites owner feedback from 2026-08-02). The list
doesn't call it; it calls bare `merchantText()`. So even *before* a payee is manually assigned, a
receipt with city+country but no name (exactly the owner's starting point) shows fully blank in the
list when `- city - country` is already sitting there, ready-made, and unused for this column.

## Shape of a fix (not decided here)

A sensible display precedence for the list cell, most-authoritative first:

1. The assigned payee's name (`Receipt.payeeId`, once set) — the operator's confirmed answer.
2. `Receipt.merchantDisplay()` — the AI's best composite guess, for anything not yet reviewed.
3. Blank — nothing parsed, nothing assigned yet.

Implementation-wise, `receipts` already depends on `ledger` elsewhere (e.g.
`ReceiptEditorService.java:10` imports `ledger.PayeeService`), so reading a payee name from the
register list crosses no new module boundary. `PayeeRepository` (`ledger/repository/
PayeeRepository.java`) currently only has `findById` (single) — resolving one payee per row in a
loop would be an N+1 over the list; a bulk lookup (a `findByIds`, or joining `payee` directly into
`findForRegister`'s own query) would be the shape to reach for instead. Whether that join belongs in
`ReceiptRepository` (crossing into `payee`'s table directly) or as a separate batched
`PayeeRepository` call composed in `ReceiptService` is a design call for whoever picks this up.

## Owner decisions (triage, 2026-08-10)

Verified against the current code: every claim in "What exists today" and "A related, smaller gap"
holds — the list template reads bare `merchantText()`, `findForRegister` has no join to `payee`,
`PayeeRepository` has only a single-id `findById`, and `merchantDisplay()` exists but is unused by
the list.

1. **Display precedence confirmed** — assigned payee's name, else `merchantDisplay()`'s composite,
   else blank.
2. **Join vs. batched-lookup** left as an implementation call for whoever picks this up, as
   originally scoped — not a product decision.

## Agent Brief

**Category:** bug
**Summary:** The register list's Merchant column only ever reads the raw AI parse
(`Receipt.merchantText`) and never reflects a payee the operator has since assigned in the editor,
nor falls back to the AI's partial name/city/country guess — so a receipt the owner has already
fixed up still shows blank (or an incomplete name) back on the list.

**Current behavior:**
The list's Merchant cell renders `Receipt.merchantText()` directly. That field is, by design, the
frozen raw AI parse fact — it is never updated when the operator assigns or corrects a payee during
post-process review (that goes to the separate `Receipt.payeeId` field instead). The list query
backing this screen does not fetch payee data at all, so even a template change alone couldn't fix
this without also reaching the payee. Independently, a receipt where the AI parsed some merchant
parts (e.g. city/country) but not others shows fully blank in the list today, even though a
composite display value for exactly that case already exists on `Receipt` and is simply not used by
this column.

**Desired behavior:**
The list's Merchant cell shows, in order of authority:
1. The name of the receipt's assigned payee, once one has been set.
2. Otherwise, the best-available composite of whatever merchant parts the AI did parse (name/city/
   country), dropping blank parts — the same composition logic `Receipt.merchantDisplay()` already
   implements.
3. Otherwise, blank (nothing parsed, nothing assigned).

Assigning or changing a receipt's payee in the editor and returning to the list must show the new
name immediately (matches the owner's exact repro) — no stale caching.

**Key interfaces (durable names — locate them, don't trust line numbers):**
- The register list's row rendering for the Merchant column — needs to resolve through the
  precedence above rather than reading the raw parsed field alone.
- `Receipt.payeeId` / the assigned payee's name — the list needs this without incurring an N+1
  lookup per row. Either extending the list's own query to bring back the payee name (a join) or a
  single batched lookup composed alongside it (e.g. resolving payee names for the whole page of
  receipts in one call) are both acceptable — whichever fits the existing query/service shape best;
  a per-row single-id lookup in a loop is not.
- `Receipt.merchantDisplay()` — reuse as-is for the fallback tier; don't reimplement its
  name/city/country composition elsewhere.

**Acceptance criteria:**
- [ ] A receipt with an assigned payee shows that payee's name in the list's Merchant column,
      regardless of what the raw AI parse says.
- [ ] A receipt with no assigned payee but a partial AI parse (e.g. city + country, no name) shows
      the composite fallback instead of blank.
- [ ] A receipt with neither an assigned payee nor any parsed merchant data still shows blank, as
      today.
- [ ] Fetching the list does not issue a per-row payee lookup — payee names for a rendered page of
      receipts are resolved in a bounded number of queries.
- [ ] Assigning/changing a payee via the editor's Save, then returning to the list, immediately
      shows the updated name.
- [ ] Test coverage matches CLAUDE.md §6's tiering: a plain join/lookup addition without grouping,
      aggregation, or more than two tables is a repository round-trip in the `integrationTest` tier,
      not `sqlLogicTest`; the display-precedence logic itself belongs wherever the equivalent
      row-assembly logic already lives and is tested today.
- [ ] `./gradlew check` green.

**Out of scope:**
- Changing what `Receipt.merchantText` stores or when it's set — it stays the frozen raw parse fact.
- Any change to the post-process editor's payee assignment/resolution flow itself
  (`ReceiptEditorService`, `PayeeService.resolvePayee`) — only how the list *displays* the result.
- Any other register-list column.

## Comments

Filed 2026-08-09 from an owner repro while reviewing a receipt with a missing AI-detected merchant
name. Left at `needs-triage`: the display precedence above is a reasonable default but not
owner-confirmed, and the join-vs-batched-lookup choice affects which module's repository the change
lands in.

> *This was generated by AI during triage.*

Triaged 2026-08-10: claims verified against current code, display precedence confirmed by the owner
(assigned payee → `merchantDisplay()` composite → blank). Join-vs-batched-lookup left as an
implementation call, as originally scoped. Moved to `ready-for-agent` with the brief above.
