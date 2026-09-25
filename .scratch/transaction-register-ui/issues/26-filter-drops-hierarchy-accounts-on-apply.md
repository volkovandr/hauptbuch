# Register account filter: All/None skip standalone accounts, and Apply drops every account in a hierarchy

Status: resolved
Category: bug
Severity: high
Area: Transaction register — account filter (`static/js/filter-groups.js`, group mode)

Reported by the owner 2026-09-25:

1. **All / None** tick or untick the accounts inside a parent/child hierarchy, but not the standalone
   top-level accounts.
2. After changing any checkbox and pressing Apply, only the standalone accounts remain in the
   register; every account inside a hierarchy is unticked and filtered out. A page refresh brings
   them back.
3. Changing only the date range and pressing Apply has the same effect.

## Cause

A regression from reporting stage d3-4 (b6aaeb5), which generalised `filter-groups.js` for the
Report page. `namedMembers()` used to select `input[name="accountId"]`, meaning every account. The
generalised version selected "has `data-filter-member` and no `data-filter-group`" instead. A
standalone account belongs to no group, so it carries no `data-filter-member` and dropped out:

- The bulk links skipped it (symptom 1).
- The all-ticked tidy-up on submit saw every *grouped* account ticked and disabled them, but still
  submitted the standalone ones. The server read those few ids as an explicit selection, so the
  hierarchies vanished (symptoms 2 and 3).

## Fix

`namedMembers()` selects every named checkbox in the panel (`input[type=checkbox][name]`, since group
toggles are unnamed). `membersOf()` keeps the per-group `data-filter-member` check. The markup is
unchanged.

## Comments

Implemented 2026-09-25 (branch `feat/reporting`). Verified with a throwaway jsdom script that loads
the live `/register` markup plus the real `filter-groups.js`: None leaves 3 accounts ticked and an
all-ticked Apply submits 3 of 19 ids before the fix; all checks pass after it.

No regression test in the repo: nothing in the build executes `filter-groups.js` (the Playwright
smoke was retired, npm is not allowed), and the integration tests only assert the server-rendered
markup, which was never wrong. Locking this down would need a JVM-side JS runner such as HtmlUnit in
the integration tier — an owner decision. Owner-confirmed 2026-09-25.
