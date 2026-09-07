# A soft-deleted person's transactions can't be reached in the register

Status: needs-triage
Category: bug
Severity: medium
Area: Transaction register — account filter (`RegisterPickerService`, `RegisterFilterViewAssembler`);
`debts` (person soft-delete)

## The problem

Reported by the owner while testing transaction-register-ui/22:

> I created a person, added a couple of transactions with him, then settled everything, then
> deleted this person. Now I cannot find a way to see that person's transactions in the register.
> It does not appear in Closed, and it does not appear in All.

When a person is soft-deleted (`PersonRepository.softDelete` sets `person.deleted_at`), their
per-currency debt-leaf **`account` rows stay live** (`account.deleted_at is null`) — deliberately,
so an old transaction's person leg still resolves (`AccountOwnerRepository` line ~51). But:

- `RegisterPickerService.membership(ALL)` does include those leaf account ids (they're live asset
  accounts), yet `RegisterFilterViewAssembler.personRows` only iterates `pickerService.livePeople()`
  (= `PersonService.balanceSummaries()` = **live** persons), so the leaves are "members" that never
  render — an invisible member, and a tab-count mismatch.
- `membership(CLOSED)` is closed real accounts only — person leaves are excluded from it entirely.

Net: the transactions exist, the debt-leaf accounts exist, but there is no UI path to filter to
them.

## What the owner wants

> I would prefer deleted persons appear in **Closed**, and also in **All** (with a label "deleted").

So: a soft-deleted person renders as a person group (umbrella → person → currency leaves, same
three-level shape) in the **Closed** and **All** panels, with the person toggle carrying a muted
"deleted" marker (mirroring the "closed" marker on closed accounts). The **Persons** and
**Last used** tabs stay live-only.

## Notes for whoever picks this up

- `open`/`closed` (an account axis) and `live`/`soft-deleted` (a row axis) are orthogonal
  (`CONTEXT.md`). "Deleted" here is the *person* being soft-deleted while the leaf account is still
  live — a third thing. Decide whether `Row` gets a `deleted` flag alongside `closed`, or a single
  `marker` string.
- No repository method currently returns `account_id → person_id` links for soft-deleted persons in
  bulk (`AccountOwnerRepository.findLiveAccountLinks()` excludes them; `findByAccountId` is one at a
  time). A new bulk query is likely needed. `findPersonNamesByAccountIds` already resolves names for
  deleted persons.
- `membership(CLOSED)` gains the soft-deleted person leaves; `membership(ALL)` already has them
  (they're live accounts) so only the rendering side changes there.
- `PersonService.balanceSummaries()` is live-only; a "roster including recently-deleted people who
  still own a debt leaf" concept is needed, or `personRows` should group the member leaves by
  person directly (account→person) rather than iterating a person roster.
- Update `docs/ui-transaction-register.md` §2.3 (the picker table) and `CONTEXT.md`.

## Comments

Filed 2026-09-07 from the owner during testing of transaction-register-ui/22.
