# Edit dock shows `to -> personal.EUR` in Category when the person is the counterpart

Status: resolved
Category: bug
Area: `operations.DockEditService` (`classifyTransfer`, `accountEntryText`), the edit dock opened
from the register's pencil button and from statements edit links
Design: `docs/ui-transaction-register.md` §3.3 / §3.5 (person sigils `for` / `by`); plan stage 8b.1

## Symptom
People page → expand a person → **View in Register** lists that person's transactions, in and out,
correctly. Clicking the pencil on a row re-opens it in the dock:

- "from …" transactions (person is the **funding**/Account side): the Account field shows the
  person correctly (`by <person>`).
- "to …" transactions (person is the **counterpart**/Category side): the Category field shows
  `to -> personal.EUR`, the cosmetic name of the person's debt leaf, not `for <person>`.

The same happens when the edit is reached from a link on the Statements page (owner does not
remember which link). Likely the same code path: both open the edit dock through `DockEditService`.

## Root cause (confirmed by reading the code)
`DockEditService.accountEntryText` already translates a person funding leaf into the
`for`/`by` sigil (its javadoc says the cosmetic `personal.<CUR>` name must never show, and that both
forms re-resolve through `/register/account/resolve` so an untouched edit round-trips).
The counterpart side has no such handling: `classifyTransfer` fills the counterpart label with
`accountService.ownAccountEntryLabel(counterpart.account())`, which for a person leaf yields the
leaf's path/name, `personal.EUR`. The person case was only covered for the funding leg.

## Accepted solution (agent brief)
Both entry points go through `DockEditService.load` (the register pencil directly; the statements
edit link via `StatementDockService` → `dockEditService.load`), so one fix covers both.

- Extract the person→sigil mapping of `accountEntryText` into one private helper
  `entryTextFor(LegAccount/Account, BigDecimal legAmount)`: if
  `personService.personNameForAccount(accountId)` is present →
  `PersonTarget.option(amount.signum() < 0 ? BY : FOR, name)`, else
  `accountService.ownAccountEntryLabel(account)`. Use it for the funding side (unchanged
  behaviour) and for the counterpart label in `classifyTransfer`.
- The sign rule is the leg's own sign, as for the funding side: counterpart leg positive (you paid
  the person, they owe you) → `for <person>`; negative → `by <person>`. `classifyTransfer` needs
  the counterpart's posting amount at the point it builds the label (it has `counterpart.posting()`).
- Round-trip check: Category text `for <person>` goes through `/categories/resolve` and must land
  on the same person leaf and direction, so an untouched Save does not change the transaction.
- Out of scope: both legs being person leaves (a person-to-person move is not a supported shape);
  the split edit path (`SplitEditService`) has its own mapping — do not touch it here, but if the
  same `personal.<CUR>` leak shows there, record it as a new issue instead of widening this one.

## Tests (indicative)
- `test` (`DockEditServiceTest`, mocks): editing a transaction whose counterpart is a person's leaf
  yields `for <name>` / `by <name>` by sign, never the leaf label; the funding case stays as is.
- `integrationTest`: MockMvc open-edit on such a transaction renders the sigil in the Category field.
- Related to statements issue 27 (the picker/resolve race for `for <person>`): the pre-filled value
  must resolve cleanly on submit.

## Comments
