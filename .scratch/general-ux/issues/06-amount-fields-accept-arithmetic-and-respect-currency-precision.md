# Amount fields accept arithmetic; typed amounts respect the currency's precision

Status: open

## Symptom
1. An amount field takes only a plain number. To enter `12.12 + 54.23` the operator works it out
   elsewhere first. (Microsoft Money had a pop-up calculator; the owner found it unclear what had
   happened after pressing `=`. Typed text that stays visible and editable is the wanted shape.)
2. A typed amount with more decimals than its currency allows is stored as typed. `33.333` EUR goes
   through `MoneyFormat.parse` (which returns the number as typed) into `posting.amount`
   (`numeric(19,4)`) as `33,3330`. The register displays `33,33` (`MoneyFactory` rounds for
   display), so the stray fraction is invisible but sits in the balance. Not yet confirmed by a
   test: the dock commit path (`DockCommitService.requiredMagnitude`) only takes `.abs()`.

## Root cause
1. No expression support anywhere: `MoneyFormat.parse` (server) and `parseGerman` in `keyboard.js`
   (live split readout) both read a single number.
2. Nothing checks a typed amount against `currency.minor_units` before it becomes a posting.

## Accepted solution (owner's decisions)

### Part A: precision (do first; Part B relies on it)
- A typed amount may have **at most the currency's `minor_units` decimals** (2 for EUR, 0 for
  JPY). More decimals are refused with a validation error naming the currency's limit. It is not
  rounded silently.
- The check sits where typed text becomes a posting amount (every `MoneyFormat.parse` call site
  that feeds a posting: dock commit, split, settings opening balance, accounts, import, statements,
  recurring). Exchange rates are not amounts and keep their own scale.
- Computed legs (FX residuals, allocations) are not typed input and are out of scope here. See
  `people-management/issues/02-*` for the one case that deliberately goes below the minor unit.

### Part B: arithmetic in amount fields
- Operators `+ - * /`, brackets, and an optional leading sign. Each number inside the formula is
  read with today's lenient rule (the last `.`/`,` is the decimal point), so `1.234,56 + 12.12`
  works.
- **When the field is left** and the formula is valid, the text is replaced by the result. A plain
  number is left exactly as typed, so `33.333` stays and Part A refuses it on save. **The result of
  a calculation** is rounded half-up to the currency's `minor_units`: `100/3` becomes `33,33`.
  Three such lines leave `0,01` remaining, which is shown honestly and not spread out.
- **A formula that is invalid when the field is left** stays as typed and the field is marked
  invalid (red) so it can be corrected. It is **never** marked red while the operator is still
  typing: an unclosed bracket mid-entry is normal.
- **While typing**, the live readouts (split `remaining`, pay/receive cue, Save label) evaluate the
  formula on every keystroke. While the formula is incomplete or invalid, they keep the **last valid
  value** (zero if there never was one), so `12 + (5` holds the readout at `12`.
- Fields opt in by markup (e.g. a `data-calc` attribute) and carry their currency's `minor_units`
  (e.g. `data-minor-units`). In the split panel that value follows a currency change.
- Lives in **`keyboard.js`**, which already owns the amount readouts. It is not a fourth JS leaf.
- **Server fallback:** `MoneyFormat.parse` evaluates the same formula language, so a formula that
  reaches the server without JS still works (§1.6 graceful degradation). The JS and Java evaluators
  are kept in step by one shared list of test cases (input → result or error) that both suites run.
- Once the field is left, the formula is gone; native undo does not bring it back. Accepted for v1.

## Open
- Whether exchange-rate fields also get arithmetic (e.g. `1/0.93`). They need more decimals than an
  amount, so the rounding rule would differ. Not decided; amounts only for now.
