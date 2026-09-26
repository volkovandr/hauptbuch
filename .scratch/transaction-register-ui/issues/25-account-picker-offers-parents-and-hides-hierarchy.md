# The register's Account picker (and its transfer targets) offers parent accounts and shows no hierarchy

Status: ready-for-agent
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

2026-09-26: the same option source also feeds the receipt screen's paying-account `<select>`. Filed
as `receipt-processing/32`; fix them together.

### Triage 2026-09-26

> *This was generated by AI during triage.*

Owner decision: fix the own-account picker **everywhere at once**, not one screen at a time. This
brief covers three issues: this one (register dock + split panel + transfer targets),
`receipt-processing/32` (receipt paying-account `<select>`), and `people-management/01` (Settle-up
funding-account `<select>`). All three move to `ready-for-agent`, and this brief is the contract for
all of them.

Checked against the code and confirmed. The post-to set (`RegisterService.pickable`) and
`SettleUpService.pickableAccounts` both filter only open + non-person, with no posting-leaf test.
`findOwnAccountByName` matches the bare name only. The receipt Confirm gate
(`ReceiptConfirmGate.checkAccount`) only checks that the account exists. A parent is first refused
by `LedgerService.requireLeafAccount` at commit. Triage also found a latent bug: the `(CUR)` suffix
does **not** actually disambiguate. The resolver looks up by name alone and only compares the suffix
afterwards, so `Card (EUR)` and `Card (CHF)` both fail as ambiguous. The rework below fixes that as
well.

## Agent Brief

**Category:** bug
**Summary:** Every own-account picker offers posting leaves only, labelled with their full
`Parent - Leaf` path. Every account resolver accepts that label, and a parent is refused before
commit.

**Current behavior:**
Four surfaces offer "an own account to post to": the register dock's Account field, the split
panel's Account field, the `To → …` / `From ← …` transfer targets in the Category datalist (dock,
split, and receipt lines), the receipt screen's paying-account `<select>`, and the Settle-up
funding-account `<select>`. Every one of them lists parent accounts next to leaves, by bare name
(`Credit card (EUR)`, `To → Credit card`). Picking a parent gets through every screen-level check
and is only refused by the ledger's leaves-only guard at commit, which reads as a broken
Save/Confirm. Two same-named leaves under different parents can't be told apart. Typing either name
resolves to nothing, with an error saying no such account exists.

**Desired behavior:**
- **One post-to set, shared by every surface:** open, live, non-person, own (asset/liability)
  **posting leaves**. A leaf is an account with no real (non-currency-leaf) children, the same test
  `AccountService.findPostableLeafPaths` already applies to categories. Build it in one place and
  have the register view, the receipt screen, and Settle-up all consume it. Don't give each screen
  its own filter.
- **One label format:** the full root-to-leaf path joined with ` - ` (the Category picker's idiom),
  followed by the existing ` (CUR)` suffix: `BankAaa - Credit card (EUR)`. A top-level leaf is
  unchanged: `Cash (EUR)`. Transfer targets carry the same label, suffix included:
  `To → BankAaa - Credit card (EUR)` (owner, 2026-09-26 — without the currency, same-named
  accounts in two currencies were indistinguishable and could not round-trip an edit). Options
  are sorted by path, case-insensitively.
- **Suffix rules (owner, 2026-09-26):**
  1. Only the leaf carries the suffix. Parent segments of the path never get one:
     `BankAaa - Credit card (EUR)`, never `BankAaa (EUR) - Credit card (EUR)`.
  2. No second suffix when the leaf's name already ends with **its own** currency code as a
     separate token (case-insensitive, preceded by a non-letter): `BankAaa-EUR`, `Cash EUR`, and
     `Card (EUR)` render as-is, not `BankAaa-EUR (EUR)`. A name ending in a *different* code, or in
     letters that merely start with it (`EUREX`), still gets the suffix. The rule lives in the one
     label formatter, not in callers.
- **Resolution tries the whole text as a name/path first**, and only then splits off a trailing
  `(CUR)`, because an account may be literally named `Card (EUR)` (rule 2 means its label carries no
  extra suffix to strip). It then accepts, in order of precedence: the full path with or without the suffix, and a
  bare leaf name with or without the suffix **when it identifies exactly one post-to account**. The
  `(CUR)` suffix really narrows the match, so `Card (CHF)` resolves when `Card` exists in EUR and
  CHF.
- **Refusals are specific and happen inline, before commit:**
  - Text that resolves to a parent account: "'BankAaa' is a group — pick one of its accounts"
    (wording is the agent's call, the meaning is not).
  - An ambiguous bare name: list the matching candidates' full labels. Don't claim none exist.
  - An unknown name: the existing message.
- **Edit mode round-trips.** Every place that rebuilds entry text for an existing transaction
  (dock edit pre-fill, split-panel legs, receipt lines seeded as transfers, the resolved-transfer
  echo in the category resolver) emits the new path label. Re-saving an unedited transaction must
  therefore resolve to the same account.
- **The receipt and Settle-up `<select>`s** show the same labels. Their server-side checks refuse a
  posted parent id with a clear inline message: the receipt Confirm gate at Confirm, Settle-up at
  submit. A stale form or hand-crafted POST therefore never reaches the ledger with a parent.

**Key interfaces:**
- `AccountEntryLabel` owns the label round-trip (`format` / `parse`). The path-bearing label must
  go through it, or through a single successor that keeps both halves together, as its Javadoc
  demands. Don't compose labels ad hoc in templates or services.
- `TransferTarget.option` / `TransferTarget.parse`: the `To →` / `From ←` round-trip. Move it
  together with `AccountEntryLabel`.
- `AccountService.findOwnAccountByName(String)` returns `Optional<Account>`, with ambiguity
  collapsed to empty. It needs a richer replacement that can tell *not found*, *ambiguous (with
  candidates)*, and *is a parent* apart, and that uses the path and the currency. Its callers are
  the dock account resolver (`DockAccountResolutionService`) and the category/transfer resolver in
  `categories`.
- The view's account options (`RegisterView.RegisterAccountOption`, rendered via `entryValue()` by
  the dock, the split panel, and the receipt `<select>`), and Settle-up's account list. All read
  the one shared post-to set.
- `ReceiptConfirmGate` and the Settle-up submit path get a leaf check.

**Acceptance criteria:**
- [ ] With `BankAaa` holding children `Credit card` and `Debit card` (EUR), the register dock's
      Account datalist offers `BankAaa - Credit card (EUR)` and `BankAaa - Debit card (EUR)` and does
      **not** offer `BankAaa (EUR)`. The same holds for the split panel, the receipt paying-account
      `<select>`, and the Settle-up `<select>`.
- [ ] The Category datalist's transfer targets offer `To → BankAaa - Credit card (EUR)` /
      `From ← BankAaa - Credit card (EUR)` and no targets for `BankAaa` itself.
- [ ] With `BankAaa - Credit card` and `BankBbb - Credit card` both live: each path resolves to its
      own account, and bare `Credit card` is refused with a message naming both paths.
- [ ] With `Card` in EUR and in CHF, `Card (CHF)` resolves to the CHF account.
- [ ] Typing or posting a parent (dock text, transfer target, receipt Confirm with a parent id,
      Settle-up submit with a parent id) is refused inline with a group-specific message. The ledger's
      leaves-only guard is never what reports it.
- [ ] A bare unambiguous name (`Cash`, `Cash (EUR)`) still resolves, so existing habits keep
      working.
- [ ] A leaf named `BankAaa-EUR` (currency EUR) is labelled `BankAaa-EUR`, one named `Card (EUR)`
      is labelled `Card (EUR)`, and each resolves back to its own account from that label. A leaf
      named `Travel-EUR` in CHF is labelled `Travel-EUR (CHF)`. A parent segment of a path never
      carries a suffix.
- [ ] Opening an existing transaction for edit (simple and split, including a transfer) and
      re-saving it without changes succeeds and books to the same accounts.
- [ ] Unit tests cover the resolver's outcomes (path, bare, suffix-narrowed, ambiguous, parent,
      unknown). The post-to-set query/composition is tested in the tier its SQL dictates (CLAUDE.md
      §6). Existing MockMvc acceptance tests for the dock, split, receipt editor, and Settle-up are
      updated to the new labels, and each gains a parent-refusal case.
- [ ] `./gradlew check` is green.

**Out of scope:**
- Replacing native `<datalist>`/`<select>` with a custom widget or adding JS (rejected in issue 03,
  CLAUDE.md §1.6).
- The register's **filter** account picker (read set, issue 22/26). It deliberately includes
  parents, closed accounts, and person leaves.
- Paying-account **auto-detection** (`findDetectionCandidates`) offering a parent. That only
  happens if the operator gave a parent detection labels. The new receipt Confirm-gate leaf check
  covers it, so don't change the detector.
- Category pickers (already fixed by issue 03), and the import account map.

**Suggested slicing (for a reviewable diff, CLAUDE.md §0):** (1) shared post-to set + path labels +
richer resolver, with the register dock/split/transfer targets moved over; (2) receipt `<select>` +
Confirm-gate leaf check; (3) Settle-up `<select>` + submit check. Each slice ships green.

2026-09-26 — implemented on branch `feat/account-picker-leaves`, awaiting owner confirmation:
`7438580` (register), `2b710a3` (receipt), `1ba4514` (Settle-up), plus a follow-up after review
(transfer targets carry the currency suffix; one shared group check, `PostToAccountService.groupOf`;
edit round-trip tests for simple and split transfers). The split panel's Account field resolves
through the dock's `/register/account/resolve`, so the dock's group-refusal test covers it.
