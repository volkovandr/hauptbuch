# Transaction currency isn't pre-selected based on the chosen Payee

Status: ready-for-agent
Category: enhancement
Severity: low
Area: Transaction register — entry

A given payee/shop is usually always paid in the same currency. Request: pre-select the currency
based on the most recent (or most common) transaction with the same payee, rather than defaulting
purely off the funding account.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.

Triaged 2026-10-02 with `04` (field order). Owner decision: pre-select the Currency picker from the
**most recent transaction with the same Account + Payee combination**; with no such transaction,
default to the account's currency. The picker now sits right after Payee (`04`), so the suggestion
lands before any amount is typed. The operator can still change it.
