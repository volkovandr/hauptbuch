# Inbound register links show the wrong date range

Status: resolved
Category: bug
Severity: minor
Area: Transaction register — inbound links

## Symptom
Two kinds of link into the register landed on a misleading range:
- People "View in register" passes only the accounts; once the register's default range became
  the last 90 days (issue 12) it showed only 90 days of a person's debt thread.
- `/register?selected=<id>` (receipt "Edit transaction", report drill-down, statements) set From to
  the transaction's date and left To open, so the register showed an arbitrary number of later rows
  with the transaction somewhere on top.

## Accepted solution (owner's decisions, 2026-10-21)
- The People link passes `fromDate=1970-01-01` (`RegisterFilter.ALL_TIME_FROM`), so the whole
  thread shows. Landing-page account links keep the 90-day default: the range is visible in the
  filter and the operator can widen it.
- The `selected=` jump sets From **and** To to the transaction's date.

## Comments
Resolved 2026-10-21. Related: issue 12 (default range now last 90 days).
