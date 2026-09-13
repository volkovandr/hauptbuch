# Offer the PC register's state/range filters on the mobile capture grid

Status: needs-triage
Category: enhancement
Severity: low
Area: Receipts — mobile capture (`ReceiptCaptureController`, `receipt-capture.html`)

Split off from [issue 27](27-mobile-capture-shows-all-receipts-not-just-queue.md) at the owner's
direction (2026-09-13): issue 27 caps the mobile grid to the working queue with no filter control.
That means once a receipt leaves the queue (is committed), there is no way to reach it from the
phone at all — e.g. to re-view a photo of an old receipt without opening the PC.

## The idea

Reuse the PC register's existing filter vocabulary (`ReceiptFilters`:
`STATE_QUEUE`/`STATE_ALL`/`STATE_VOIDED`, `RANGE_90D`/`RANGE_1Y`/`RANGE_ALL`) on
`/receipts/capture` too, defaulting to the working queue (matching issue 27's behavior out of the
box) but letting the owner switch to "all" or a wider date range from the phone when they actually
need to. Not a second filter vocabulary — the same one, threaded onto a second screen.

## Not yet decided

- Whether a full `state`/`range` control pair belongs on a page deliberately kept thin
  (CLAUDE.md §1.6 — "the phone is a capture device, not a finance console"), or whether a
  narrower affordance (e.g. just an "all" toggle) fits the mobile page's character better.
- What the control looks like without adding bespoke JS beyond the existing `keyboard.js` leaf
  (plain links, or a `<select>` that auto-submits).

Deferred — not scheduled. Revisit if the owner wants to reach non-queue receipts from the phone in
practice after issue 27 ships.

## Comments

Filed 2026-09-13, split from issue 27's "option 2" at the owner's direction.
