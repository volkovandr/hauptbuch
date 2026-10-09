# Statement page: filter to the lines that need attention; collapse the contents editor

Status: resolved

## Symptom
On a long statement where every line but one is matched, the one missing line is easy to scroll past.
The "Lines" editor (every field of every line, with Save lines) sits below the matching table, looks
almost like it, and is almost never used.

## Accepted solution (owner, 2026-10-09)
- **Filter:** the Matching table has two views — **All** and **Needs attention** (every line whose
  status is not `matched`: missing, proposed, overlap, amount/account differs, problem). The choice
  is kept in the HTTP session (`?show=all|attention`), so it survives every action's redirect, and
  applies to every statement. Default: All. A line the operator just clicked (its dock open, or its
  refusal shown) stays visible whatever the filter says; once it becomes matched it drops out of
  Needs attention, which is the point.
- **Lines editor:** collapsed under a `<details>` summary "Edit statement contents" (no JS).

## Out of scope
Remembering the choice across sessions; a "jump to first missing line" link.
