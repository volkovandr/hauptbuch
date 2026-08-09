# Register list keeps showing a blank Merchant even after the receipt has a proper Payee

Status: needs-triage
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

## Comments

Filed 2026-08-09 from an owner repro while reviewing a receipt with a missing AI-detected merchant
name. Left at `needs-triage`: the display precedence above is a reasonable default but not
owner-confirmed, and the join-vs-batched-lookup choice affects which module's repository the change
lands in.
