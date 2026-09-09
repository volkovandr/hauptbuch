# The register's Account picker (and its transfer targets) offers parent accounts and shows no hierarchy

Status: needs-triage
Category: bug
Severity: medium
Area: Transaction register — entry (`RegisterService.accountOptions` / `transferTargets`, `DockAccountResolutionService`, `AccountService.findOwnAccountByName`)

Reported by the owner 2026-09-08, after account hierarchy came into use (account-management/03
re-parenting shipped with transaction-register-ui/22):

> The account and the category pickers in the Register do allow selecting Parent accounts. Luckily
> such transactions do not pass the validation, but this should not be possible from the beginning.
> [...] These pickers do not indicate the account hierarchy. [...] if I have a parent account
> `BankAaa` and two children `Credit card` and `Debit card`, they should appear as
> `BankAaa - Credit Card (EUR)`, `BankAaa - Debit Card (EUR)`. But currently I see three accounts.
> [...] I'm not sure about `Credit card`, because I might have a credit card in a different bank as
> well, and thus I would see multiple `Credit card`.

## Filed as one issue, not two

The two complaints are one defect with one fix, and they are causally coupled: filtering parents out
of the list **makes the naming ambiguity worse**, because removing `BankAaa` leaves two
identically-labelled `Credit card` options with nothing to tell them apart. Neither half is
shippable alone.

This also mirrors the shape of the already-resolved **transaction-register-ui/03**, which handled
exactly this pair for the *Category* picker as a single "bug with an enhancement rider — the
composed hierarchy labels".

## Where it actually is (three option sources, one already fixed)

`RegisterService` builds four datalist sources. Only the category one was fixed by issue 03:

| Source | Leaves only? | Hierarchy in the label? |
|---|---|---|
| `categoryOptions()` — `findPostableLeafPaths(CATEGORY_TYPES, " - ")` | ✅ yes | ✅ `Parent - Child` (issue 03) |
| `accountOptions(pickable)` — `a.name()` | ❌ **no** | ❌ bare name |
| `transferTargets(pickable)` — `To → a.name()` / `From ← a.name()` | ❌ **no** | ❌ bare name |
| `personTargets()` | n/a (person leaves) | n/a |

`pickable()` filters only `!personLeaf()` (over `openOwnAccounts()`); there is **no posting-leaf
filter** on either account source.

**This is why the owner sees the defect in the Category picker too, even though issue 03 fixed
it.** The Category datalist (`entry-dock.html` 212–225) is `categories() + transferTargets() +
personTargets()` — so a parent account reaches the Category field through the *transfer-target*
path (`To → BankAaa`), which issue 03 never touched. Worth stating plainly during triage, or the
fix will be scoped to the wrong method.

## The two failure modes today

1. **A parent is offered and accepted, and only the engine refuses it.** `DockAccountResolutionService
   .resolveAccount` → `AccountService.findOwnAccountByName` applies no leaf test, so a parent
   resolves to a real account id and carries all the way to commit, where
   `LedgerService.requireLeafAccount` rejects it (leaves-only, data-model §5). The owner's "luckily
   such transactions do not pass the validation" is exactly right — but the user only discovers it
   at Save, which is the same "reads as a broken Save" complaint issue 03 was filed for.
2. **A same-named child is unreachable, with a misleading error.** `findOwnAccountByName` returns
   the match only when `matches.size() == 1`, so it *refuses* ambiguity rather than guessing — good,
   **no silent wrong-account money bug**. But with `BankAaa - Credit card` and `BankBbb - Credit
   card` both live, typing `Credit card` resolves to nothing and the dock says *"No open account
   named 'Credit card'"* — which is false and actively confusing, and there is no string the user
   can type that reaches either account. The account becomes unenterable.

## Expected

- Both account sources offer **posting leaves only** — a parent with real children is not an option
  in the Account field, nor as a `To →`/`From ←` transfer target.
- Options carry the full path in the same ` - ` idiom the Category picker uses, with the existing
  `(CUR)` suffix preserved: `BankAaa - Credit card (EUR)`.
- The resolver accepts the composed path (and keeps accepting a bare unambiguous name), so
  `BankAaa - Credit card (EUR)`, `BankAaa - Credit card`, and — where still unambiguous — `Cash` all
  work. An ambiguous bare name should say *which* accounts it matched, not claim none exist.
- A parent submitted anyway (typed by hand) is refused inline at resolve time with a clear message,
  never carried to commit — the rule issue 03 established for categories.

## Notes for whoever picks this up

- `AccountService.findPostableLeafPaths(types, separator)` is **type-agnostic** (it takes `types`)
  and already excludes real parents and currency leaves, so it fits asset/liability — this answers
  the open question raised in `people-management/01`. But it does **not** filter `closedAt` or
  `personLeaf`, which the register's post-to set requires; combine it with the existing `pickable()`
  filters rather than loosening either.
- `AccountEntryLabel.parse` / `.format` own the `Name (CUR)` round-trip and are used by the dock,
  the split panel (`SplitPanelAssembler.accountEntryText`), and `DockEditService.accountEntryText`.
  Any label change must move through that one pair, or the edit-mode reconstruction will stop
  matching what the picker offers.
- `TransferTarget.option(direction, name)` composes the `To → name` string and `TransferTarget.parse`
  reverses it; the transfer counterpart resolves via the same `findOwnAccountByName`. Path-labelled
  transfer targets need both halves moved together.
- **Do this with `people-management/01`.** That issue is the identical defect on the Settle-up
  screen's funding-account `<select>` (`SettleUpService.pickableAccounts()`), and it already flags
  the register's Account datalist as needing to stay consistent. One fix, two callers; a `<select>`
  can't indent either, so the same composed-path label serves both.
- Out of scope, per issue 03's precedent: replacing the native datalist with a custom widget (loses
  free-text create and the `To →`/`for` sigils, and adds app-wide JS — already rejected).

## Comments

Filed 2026-09-08 from the owner. Real bank name in the report replaced with `BankAaa`/`BankBbb`
per CLAUDE.md §5.
