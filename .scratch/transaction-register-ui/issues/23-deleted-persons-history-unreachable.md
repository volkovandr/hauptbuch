# A soft-deleted person's transactions can't be reached in the register

Status: ready
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

## Resolved design (grilled with the owner 2026-09-07)

### Behaviour

- A soft-deleted person that **still owns a live per-currency leaf** renders as a normal person
  group (umbrella → person toggle → currency-leaf checkboxes) in the **Closed** and **All** panels.
- The **per-person toggle** carries an inert muted `deleted` marker, mirroring the `closed` marker
  on closed real-account leaves. The umbrella and the currency leaves carry nothing.
- Deleted and live persons interleave in **one alphabetical-by-name list** under the single
  `Persons` umbrella (in All; Closed shows deleted persons only).
- **Persons** and **Last used** stay live-only. This *fixes an existing bug*: `membership(LAST_USED)`
  currently admits a just-settled-then-deleted person's leaf (recent settling activity) with no row
  to match — a silent member and a tab-count mismatch.
- Revival is fully derived from `person.deleted_at` at render time — no stored state.

### Invariant (pinned in `RegisterPickerService.membership` Javadoc)

> A picker's membership is exactly the leaf ids its panel renders as checkboxes; both are resolved
> from the same sources and must not drift.

### Mechanics

| Piece | Change |
|---|---|
| `PersonMergeService.merge()` | After reassigning source-leaf postings, soft-delete the now-empty source leaves, then soft-delete the source person — one transaction. Keeps merged-away empties out of the account tree so no posting-gate is needed anywhere. |
| `PersonService.deletedPeople()` | New — the soft-deleted counterpart of `balanceSummaries()`, reusing `PersonBalanceSummary` (empty `balances`, still-live leaves in `accountIds`), name-ordered. The single new read. |
| `AccountOwnerRepository.findSoftDeletedPersonLeaves()` | New bulk query behind it: `account_owner ⋈ person ⋈ account` where `person.deleted_at is not null and account.deleted_at is null`, ordered by name then currency. A round-trip → integration tier. |
| `RegisterPickerService` | `personGroups(picker)` (merged live+deleted, alphabetical, `deleted` flag) replaces `livePeople()`. `membership(CLOSED)` gains the deleted-person leaves; `membership(LAST_USED)` drops non-live persons' leaves; `membership(ALL)` unchanged. |
| `RegisterFilterView.Row` | `boolean closed` component → `Marker { NONE, CLOSED, DELETED }`; `closed()` / `deleted()` derived accessors kept. |
| `RegisterFilterViewAssembler` | `showPeople` gains `CLOSED`. `personRows` renders the ordered groups, setting `Marker.DELETED` on the toggle. |
| `fragments/register-filter.html`, `register.css` | second muted `<span>` for `deleted`, sharing the `closed` styling. |
| `register.html`, `filter-groups.js` | no change (empty-`<ul>` / `--split` logic and markup-driven grouping already cover it). |

### Tests

- `AccountOwnerRepositoryIntegrationTest` — round-trip for the new query (includes soft-deleted
  owner, excludes live owner and a soft-deleted leaf).
- `PersonServiceTest` — `deletedPeople()` groups rows by person, name-ordered.
- `PersonMergeServiceTest` — source leaves soft-deleted after the fold; `PersonMergeScreenIntegrationTest` — source leaves `deleted_at`-stamped.
- `RegisterPickerServiceTest` — CLOSED includes deleted-person leaves; LAST_USED excludes them.
- `RegisterFilterViewAssemblerTest` — Closed panel renders a deleted person with `Marker.DELETED`.
- `RegisterScreenIntegrationTest` — one case: Closed panel shows a settled-then-deleted person with
  the `deleted` marker and the right ticked count.
- No Playwright (not a money flow).

### Docs

- `docs/ui-transaction-register.md` §2.3 (Closed + All rows; the "wherever person leaves appear"
  sentence) and §2.3a.
- `CONTEXT.md` — new `### Accounts` term for a soft-deleted person whose leaf is still live.

## Comments

Filed 2026-09-07 from the owner during testing of transaction-register-ui/22.
Design grilled and resolved with the owner the same day; ready to implement.
