# Signs in reports: income and spending look the same, and spending doesn't read as negative

Status: needs-triage
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module: `CellValuation`, measure/leg presentation; `docs/reporting.md` §5.3)

## What happens

Reports show the raw posting sign (`+` = debit, `−` = credit, data-model §4), so which sign a number
gets depends on the kind of account it's on:

- With income and expense categories side by side, **Salary** and **Food** can show the **same
  sign**. The owner finds this very confusing.
- Spending from an account reads as a positive number on the expense side (`Food +50`), while the
  owner intuitively expects **spending to be negative** and gains positive (e.g. `BankAaa → Food −50`
  in issue 19's Example 1).

## Wanted (owner)

Numbers whose sign matches the reader's intuition: money going out negative, money coming in
positive, and income vs expense distinguishable at a glance. **Not designed yet.** This needs its own
thinking session.

## Starting points (owner, not decisions)

- With Account on both rows and columns (issue 19's Account × Account), let the operator choose
  **Account / debit** on rows and **Account / credit** on columns (or the reverse), so each axis
  has one side of the transfer and one sign.
- For Account × Category the owner has no answer yet.

## Notes for triage

- `CellValuation` already flips the sign for credit-natural scopes (`CREDIT_NATURAL_TYPES`), so there
  is precedent for a presentation-sign rule. Check what it does today and why before designing
  anything new.
- The sign convention in the ledger (+ debit / − credit, sum to zero) must not change. This is purely
  how a report *presents* numbers.
- Interacts with pie legality (§7.4: a pie refuses a measure that can go negative) and with issue
  19's counterpart signs.

## Comments

Filed 2026-09-24 from the owner grilling session on issue 08 (Q10). Deliberately kept out of 08 and
19.
