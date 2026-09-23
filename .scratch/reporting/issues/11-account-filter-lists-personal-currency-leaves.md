# Account filter lists anonymous `personal.<CUR>` entries instead of one "Personal debts" entry

Status: needs-triage
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
