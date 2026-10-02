# One fixed field order for the entry dock, whatever the currencies or split state

Status: ready-for-agent
Category: enhancement
Severity: medium
Area: Transaction register — entry dock + split panel header; recurring template editor (`recurring/02`)

## Symptom (owner walk-through, 2026-10-02, base EUR)

1. **USD account, USD transaction** — one `Amount (USD)` field. Fine.
2. **EUR account, USD transaction** — tab order is Date, Account, Payee, `Amount (EUR)`, Category,
   Currency, then `Amount (USD)` appears. The operator does not know the EUR amount when passing
   `Amount (EUR)`, so has to tab back, work it out by hand, then tab forward to Save.
3. **Same as 2, split** — pressing Split rearranges the row: Currency jumps in front of Payee,
   Category goes (expected), the amounts become `Total (USD)` then `Off account (EUR)`.
4. **USD account, PLN transaction** — as 2/3 plus a `Base amount` field after `Amount (PLN)`; the
   order is even less predictable.

The field order changes with the currency combination and with the split toggle.

## Accepted solution (owner decisions, 2026-10-02)

Fixed tab order, also left-to-right:

1. **Date**
2. **Account**
3. **Payee**
4. **Currency** (the transaction currency) and the Split (`+`) button. Pre-selected per `17`.
5. **`Amount (<transaction currency>)`** — no default.
6. **`Off account (<account currency>)`** — only when the account's currency differs from the
   transaction currency. Auto-suggested from the rate feed (`27`).
7. **`Base (<base currency>)`** — only when neither the account nor the transaction currency is
   base. Auto-suggested from the rate feed (`27`).
8. **Category**
9. **Note**
10. **Tags**

- **Split keeps the order.** Pressing Split only removes the Category picker; nothing else moves.
  The split *lines* and the receipt editor keep their current layout.
- **Naming.** The account-currency amount is labelled `Off account (CODE)` in the simple dock too,
  as the split header already does — one name on both surfaces.
- **The transaction-currency amount becomes the primary one.** Today the dock's `amount` is in the
  account currency and `categoryAmount` is the extra field; this flips it to match the split header
  and the receipt editor. That touches commit and edit reconstruction (`DockCommitService`,
  `DockEditService`), not just the template — commit and reconstruct must move together (the trap
  from `06`/`21`).
- **Cross-currency transfers — option (a).** A transfer's target (picked in Category, after the
  amounts) fixes the counterpart currency, as today. Picking a target in another currency
  **auto-switches the Currency picker to the target account's currency**; amounts already typed
  **stay as they are** — re-checking them is the operator's responsibility. Owner will try how it
  feels and may revisit (alternative considered: currency first, transfer targets filtered to it,
  as the split line rule does). This also removes today's inconsistency where the picker is shown
  on a transfer but silently ignored.
- A transaction still spans at most two native currencies plus base; no change to that rule.

## Related

- `17` — currency pre-selection (feeds field 4).
- `27` — rate suggestion for fields 6 and 7.
- `recurring/02` — the recurring template editor adopts the same order.
- `21` — this moves the simple dock toward the split model's semantics but does not depend on it.

## Comments

Originally filed with an earlier preferred order (Currency before Payee, account-currency amount
first). Rewritten 2026-10-02 with the owner's revised order and decisions above.
