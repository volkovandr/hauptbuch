# Account filter lists anonymous `personal.<CUR>` entries instead of one "Personal debts" entry

Status: resolved
Category: bug
Severity: low
Area: Reporting (`analytics` module: Account filter options, `fragments/report-filters.html`)

## What happens

The Report page's **Account** filter section lists per-person debt leaves under their cosmetic
account name, `personal.<CUR>` (e.g. `personal.EUR` several times, once per person). The operator
can't tell which person each entry belongs to, so the entries are unusable.

The report *rows* already merge these leaves into "Personal debts (<CUR>)" buckets
(`ReportQueryRepository` `ACCOUNT_DIMENSION_KEY`/`_LABEL`). The filter doesn't.

## Expected (owner)

One entry, **Personal debts**, in the Account filter. Ticking it selects every per-person debt leaf
(all people, all currencies). Filtering by an individual person is already possible through the
**Person** filter.

## Notes for triage

- Ticking must store something that survives new people/currencies being added (the §11a.5
  "store the node" principle). There is no real parent account for debt leaves (`data-model.md` §7),
  so this is a synthetic node that compiles to "all `person_leaf` accounts". Issue 06 needs exactly
  this predicate for its top-level "Personal debt" row. Build it once, shared by both.
- If issue 06 ships its Personal debt → person → currency tree, the filter could mirror that tree
  instead of a single line. Decide at triage whether this issue waits for 06 or ships the one-line
  version first.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. Related:
`06-personal-debts-as-one-expandable-node-person-then-currency.md`.

Triaged 2026-09-24 (owner): **ready-for-agent, built together with issue 06**, before reporting
slice f. The filter gets the **one-line** "Personal debts" entry, not a mirror of 06's
person → currency tree: a per-person entry would duplicate the Person filter (two controls for one
meaning, the thing `reporting.md` §6.1 cut scope subtrees for). The entry is a synthetic node that
compiles to "all `person_leaf` accounts", the same predicate as 06's top-level row. Build it once.
The label is "Personal debts", shared with 06. Under issue 08's rule, ticking it makes "Personal
debts" a top-level axis node when Account is also a dimension.

Implemented 2026-09-24 with issue 06 (branch `feat/reporting`), owner-confirmed the same day. The
Account filter no longer lists debt leaves. One "Personal debts" entry stores `personal`, which
compiles to every debt leaf, and under issue 08's rule makes "Personal debts" a top-level node when
Account is also a dimension.
