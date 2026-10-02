# Cross-currency amounts are not suggested from the rate feed in the simple dock

Status: ready-for-agent
Category: enhancement
Severity: medium
Area: Transaction register — entry dock (`DockAmountFieldsService`, `CrossCurrencyFieldsService`)

## Symptom

EUR account, USD transaction (base EUR): after typing the USD amount, the EUR amount stays empty.
The operator has to compute it by hand.

## Root cause

The rate lookup exists (`ExchangeRateService.rateAsOf`, carry-forward), but the simple dock uses it
only for the base amount when neither currency is base, derived from the *account*-currency amount.
Nothing derives the account-currency amount from the transaction-currency amount — that proposal
(`CrossCurrencyFieldsService.prefillFundingTotal`) is wired only into the split panel and the
receipt editor (`SplitCurrencyService`). Also, the dock re-fetches the amount fields only on a
currency/account change, not when an amount is typed, so a proposal could not follow the typing.

## Accepted solution (owner decisions, 2026-10-02)

With the field order from `04`:

- Typing `Amount (<transaction currency>)` suggests **`Off account`** (transaction → account
  currency, triangulated through base, as `prefillFundingTotal` already does) and, when shown,
  **`Base`** (transaction amount × its rate to base), using the latest rate on or before the
  transaction date.
- **Base derives from the transaction amount**, not from `Off account`. Editing `Off account` does
  not change `Base`.
- **Re-suggest on change, never overwrite what the user typed.** Changing the transaction amount
  (or date/currency) recalculates fields that still hold a suggestion; a value the operator typed
  is left alone.
- No rate on file → the field stays blank (no guess), as the existing proposals already behave.

## Doc fix riding along

`docs/ui-transaction-register.md` §3.8a still says the implied rate is "never written back to the
`exchange_rate` feed". That is stale: data-model §3.7 (owner decision 2026-09-30) writes it back as a
`source='manual'` row. Align the register doc.

## Related

`04` (field order), `17` (currency pre-selection).

## Comments

Filed 2026-10-02 from the owner's walk-through (cases 2 and 4 in `04`).
