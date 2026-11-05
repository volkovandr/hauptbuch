# Statements: the "Filter list by account" dropdown lists only accounts that have statements

Status: resolved
Category: enhancement
Area: `statements` — `StatementController.list`, `StatementService`, `StatementRepository`,
`statements.html`
Design: `docs/statements.md` (Statements page); no domain rule involved

## Symptom
The filter above the statements table offers every open asset/liability account (20 on the
owner's ledger), although statements exist for only three. 17 of the options always give an empty
list.

## Root cause
The page passes one model attribute, `accounts` (`StatementService.statementAccounts()`: all open,
non-person asset/liability accounts), and uses it for **two different jobs**:
the upload form's account select (`upload-account`, `statements.html:40`) and the filter
(`filter-account`, `statements.html:57`). That list is right for the upload — any such account can
receive a statement — and wrong for the filter.

## Accepted solution (owner accepted the recommendations)
Give the filter its own list: the accounts that have at least one live statement.

- **Repository** (`StatementRepository`): `findLiveStatementAccounts()` returning `List<Account>`
  or a small record `(accountId, name)`:
  `select distinct a.account_id, a.name from statement s join account a on a.account_id =
  s.account_id where s.deleted_at is null order by a.name` (explicit `ON`, as per house style).
  Must **not** filter on `account.closed_at`: a statement on a since-closed account stays listed in
  the table (`accountName()` already handles that), so it must stay filterable.
- **Service:** `filterAccounts()` delegating to it.
- **Controller:** add model attribute `filterAccounts`; keep `accounts` for the upload form.
- **Template:** `filter-account` iterates `filterAccounts`; upload select unchanged.
- **Edge cases:**
  - The filter is applied (`?account=7`) and the last statement of account 7 is then deleted: 7 is
    no longer in the list and the select would silently show "All accounts" while the table is
    empty. Decide: acceptable (the table's empty state explains it) or add the selected account to
    the list anyway. Owner: **acceptable** — simplest, and a stale bookmark is the only way to
    get there.
  - Uploading the first statement for a new account makes it appear in the filter on the next page
    load; nothing to do.
  - Hide the filter entirely when fewer than two accounts have statements? Owner: keep it always visible, as today.

## Complexity
Small; roughly 30 lines plus tests, no migration, no schema change, no new module dependency
(`statement` joins `account` already in `findLiveRows`). The only trap is reusing `accounts` and
accidentally shrinking the upload form's list — which would make it impossible to upload the first
statement for a new account.

## Tests
- `integrationTest` (`StatementRepositoryIntegrationTest`): the new method returns each account once
  with several statements, excludes accounts with only soft-deleted statements, includes a closed
  account that has a live statement, and is ordered by name. It is a plain distinct/join round-trip,
  not SQL-resident logic, so no `sqlLogicTest`.
- `test` (`StatementServiceTest`): `filterAccounts()` delegates; thin.
- Controller acceptance (integration tier, MockMvc, if a statements-list test exists): the filter
  options are the used accounts, the upload options are still all open accounts.
- No browser test: no JS leaf involved.

## Comments
