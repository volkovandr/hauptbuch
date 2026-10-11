# Shared expense split evenly: person legs carry sub-cent shares, the category takes the residual

Status: open

## Symptom
The operator pays 100 EUR in a restaurant for three people (themself and two others). Today each
leg is typed by hand at currency precision, e.g. `Debt-Doe +33,33`, `Debt-Roe +33,33`,
`Restaurants +33,34`. Each person ends up owing a rounded share, and the operator has to work out
where the odd cent goes.

## Wanted outcome (owner's example)
| Leg | Amount |
|-----|--------|
| `BankAaa-EUR` (paying account) | −100,0000 |
| `Debt-Doe` (person) | +33,3333 |
| `Debt-Roe` (person) | +33,3333 |
| `Restaurants` (expense category) | +33,3334 |

The person legs carry the exact share at the column's full scale (`posting.amount` is
`numeric(19,4)`). The operator's own category leg takes the residual, so the transaction still sums
to zero exactly.

## Root cause
Nothing like this is implemented. There is no even-split helper (the equal/shares/exact split
calculator is deferred, implementation-plan, potential-feature-ideas). Under
`general-ux/issues/06-*` Part A, a *typed* amount is limited to the currency's `minor_units`, so a
person leg like `33,3333` cannot be typed either.

## Accepted so far (owner's decisions)
- Person legs of a shared expense may go **below the currency's minor unit** (up to the posting
  scale of 4). This is the deliberate exception to the typed-amount precision rule in
  `general-ux/06`. It is an outcome the app computes, not something the operator types.
- The residual goes to the operator's **expense category** leg, not to a person.

## Open (to decide before implementing; read data-model §§ on debts first)
- How the split is expressed in the UI: an even-split action on the split panel, a shares/exact
  calculator, or something else.
- How a person balance with sub-cent fractions is displayed and settled. A settle-up of
  `66,6666` across two expenses must clear the account to exactly zero, so settle-up has to pay the
  full-scale balance, not a rounded one.
- Whether the category leg may also carry 4 decimals (`33,3334`) or must be at currency
  precision. If the latter, the residual has to sit elsewhere.
- Cross-currency shared expenses (`base_amount` legs).
