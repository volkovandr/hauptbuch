# Recurring template editor's field order should match the register dock

Status: ready-for-agent
Category: enhancement
Severity: low
Area: Recurring — template editor (`recurring-editor.html`)

The recurring template editor orders the transaction fields differently from the register's entry
dock. Once `transaction-register-ui/04` lands, swap Payee and Currency here so the editor follows
the same order: Date, Account, Payee, Currency, amounts, Category, Note, Tags.

## Comments

Filed 2026-10-02 alongside the register field-order decision (`transaction-register-ui/04`).
