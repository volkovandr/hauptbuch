# The receipt list should show, and sort by, when a receipt was confirmed

Status: open
Category: enhancement
Severity: moderate
Area: Receipts — register list (`receipts.html`, `ReceiptSort`, `ReceiptRepository.findForRegister`), `receipt` table

## Symptom
A receipt confirmed a moment ago (or a few minutes ago) cannot be found in the list. The list
shows the capture date and the transaction date, but receipts are often processed long after they
were captured, so neither helps. See issue 38 for the in-the-moment fix; this is the safety net for
noticing a mistake later.

## Root cause
The confirmation time is not stored. `receipt` has `captured_at` and `transaction_id` (NULL until
committed), nothing recording when the commit happened.

## Accepted solution (owner's decisions)
- Add a nullable `confirmed_at timestamptz` to `receipt` (Flyway migration), set when
  `ReceiptCommitService.confirm` commits. A Re-enter (reopen then confirm) sets it again.
- Show it as a **new column** in the receipt list (date and time) and make it **sortable** like the
  existing four (capture, transaction date, merchant, total — `ReceiptSort`; capture and total are
  ordered in SQL, the others in memory). `confirmed_at` is a real column, so it orders in SQL like
  `captured_at`; this extends how `findForRegister` picks its sort key (today a boolean
  `sortByTotal`).
- Receipts confirmed before the migration have no value: shown empty and sorted last (try a
  backfill from the linked transaction's creation time if one exists; otherwise leave them empty).

## Tests
- `integrationTest`: migration applies and the column maps; `findForRegister` orders by
  `confirmed_at` ascending and descending, nulls last, with the existing tiebreaks.
- Confirm sets `confirmed_at` (unit test of the service orchestration with repositories mocked, plus
  the repository round-trip).
