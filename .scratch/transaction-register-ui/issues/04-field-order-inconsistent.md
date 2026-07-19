# Field order differs between simple and split transactions

Status: needs-triage
Severity: minor
Area: Transaction register

The order of fields in a simple transaction is different from the order of fields in the main
part of a split transaction. This is confusing and inconsistent — the order should be the same
in both cases.

Preferred order:

Date, Account, Currency, Payee, Total (in account currency), Off account (in transaction
currency, optional), Base (in base currency, optional), Category, Tags, Notes.

When the split button is pressed the "Category" picker disappears, but the positions of the
other fields should not change.

## Comments
