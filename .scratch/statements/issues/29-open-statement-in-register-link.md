# Statements: an "Open in register" link on the statement page

Status: resolved
Category: enhancement
Area: statement page (`statement.html`)
Design: `docs/statements.md`; the register's `accountId` / `fromDate` / `toDate` query parameters
(`RegisterController`); no domain rule involved

## Symptom
Working a statement, the operator keeps jumping to the register for the statement's account over
the statement's period, to look at what is actually booked there. Today that means opening the
register and setting the account and both dates by hand, every time.

## Owner's proposal
A link directly below the original-file link at the top of the statement page, "Open in register",
that opens the register pre-filtered to the statement's account and to the statement's period.

## Accepted solution
One link in `statement.html`, on its own line under the line holding the file-download link:

`/register?accountId=<statement account>&fromDate=<periodStart>&toDate=<periodEnd>`

- No new endpoint and no Java: the register already takes these three query parameters, and with
  no `datePreset` it uses the typed dates as given (`RegisterController.dateRange`).
- Same tab, like the page's other register links.
- The statement's exact dates, no padding for the matching window.
- `period_start` / `period_end` are nullable: a missing bound is left out of the link, so the
  register is filtered by account (and the other bound) only.

## Tests
- `integrationTest` (`StatementScreenIntegrationTest`, MockMvc): the page renders the link with the
  account and both period dates; with a null period end the link carries no `toDate`.
- No unit, browser or template-rendering test beyond that (CLAUDE.md §6).

## Comments
