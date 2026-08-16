# Receipts list: show the transaction date

Status: resolved
Category: enhancement
Severity: medium
Area: Receipts — register list screen (`receipts.html`/`ReceiptRegisterController`/
`ReceiptRepository.findForRegister`)

## Request (owner)

The receipts list only shows the capture date (when the receipt was scanned/uploaded) — that's not
enough to place a receipt in time; the owner wants the **transaction date** (the booking date of the
linked transaction) shown as well.

## What exists today

**Columns.** `receipts.html:101-113` header row is Scan / Captured / State / Merchant / Total /
Account / Transaction(🔗) / Status. Rows at `:114-156`. The **Captured** cell (`:134`) renders
`r.capturedAt()` — capture/scan time, not a booking date. The **Account** and **Transaction** cells
(`:149-150`) are currently rendered blank on purpose — the file's own header comment (`:3-11`) says
the full column set was laid out ahead of data in 9e ("a stable layout so 9e adds data, not template
churn"). There is no transaction-date column at all today.

The list query, `ReceiptRepository.findForRegister` (`ReceiptRepository.java:65-76`), is a plain
`select * from receipt` with **no joins** (the class javadoc, `:19-21`, states this explicitly) — it
returns `Receipt` records directly, no row DTO/projection. `Receipt` (`Receipt.java:53-82`) has
`capturedAt` (`:56`) and `receiptDate` (`:65`, the AI-parsed or operator-edited date *printed on the
receipt*) but no field sourced from the booked transaction. The actual booking date lives on
`Transaction.date` (`Transaction.java:26`), reachable only via `receipt.transactionId` — getting it
onto the list means a batched follow-up lookup keyed on the committed rows' transaction ids, the same
pattern `ReceiptService.merchantDisplays` (`ReceiptService.java:94-106`) already uses to resolve
payee names for the Merchant cell. `receiptDate` is not a substitute — it's the OCR/edited date on
the document, not `transaction.date`.

## Comments

Filed 2026-08-13 from an owner request, originally bundled with a sorting ask into one issue since
both touched the same list screen and query.

Split during triage (2026-08-16): the transaction-date column is small and follows an existing
pattern (`ReceiptService.merchantDisplays`'s batched lookup) with no open design questions, so it
moves straight to `ready-for-agent`. The sorting half needed real design decisions and overlapped an
undesigned app-wide backlog item — carved out to issue 11, left at `needs-triage`.

## Agent Brief

**Category:** enhancement
**Summary:** Show the linked transaction's booking date as a column in the receipts register list,
alongside the existing capture date.

**Current behavior:**
The receipts register list shows only the capture date — when the receipt was scanned/uploaded. It
has no column for the booking date of the transaction the receipt links to once committed, even
though a column slot for it already exists in the row layout (laid out ahead of data in an earlier
stage).

**Desired behavior:**
The register list gains a transaction-date column showing the linked transaction's booking date for
every receipt whose state has a linked transaction; a receipt with no linked transaction shows the
cell blank — the same "absent from the map renders blank" convention already used for the Merchant
cell. Transaction dates for the whole visible list are resolved via a single batched lookup keyed on
transaction id, not a per-row query — mirror the existing batched-lookup convention already used to
resolve payee names for the Merchant cell (one map built once for the full list, looked up per row in
the template). The existing capture-date column is untouched; this adds a column, it doesn't replace
one.

**Key interfaces:**
- The service method that builds the register's row-display data (the one that also resolves the
  Merchant-cell display) gains a sibling batched lookup: given the list of receipts, return a map
  keyed by receipt id (or transaction id) to the linked transaction's booking date.
- The register list's repository query stays a plain unjoined select — the transaction date is
  fetched via the separate batched lookup, not by adding a join to that query.
- Template: render the previously-blank transaction-date cell.

**Acceptance criteria:**
- [ ] A receipt with a linked transaction shows that transaction's booking date in the list, distinct
      from the capture-date column.
- [ ] A receipt with no linked transaction (new/reviewing/etc.) shows the transaction-date cell blank,
      not an error or a stale value.
- [ ] The register list's underlying query for the receipt rows themselves remains an unjoined,
      single-table select — transaction dates come from a separate batched lookup.
- [ ] The lookup is batched once for the whole visible list — no N+1 query per row.
- [ ] Existing columns and their order/behavior are unchanged.
- [ ] Test coverage matches the pattern used for the existing batched Merchant-cell lookup
      (unit-level for the batching/mapping logic; round-trip coverage for any new repository method).

**Out of scope:**
- Sorting by any column (tracked separately in issue 11).
- Changing what `receiptDate` (the OCR/edited date printed on the receipt) means or displays — this
  is strictly the linked transaction's booking date.
- Any change to the capture-date column or its ordering.

> *This was generated by AI during triage.*
