# A campaign already open when issue-03 landed keeps expect_file=true on accounts whose file is staged

Status: needs-triage
Category: bug
Severity: minor (pre-release; only bites a campaign open across the upgrade)

Area: Import — `expect-file` lifecycle (`ImportStagingService`, `import_account.expect_file`, issue 03)

## What happens

Issue 03 (`ed31546`) made staging a file auto-clear `expect_file` for that file's own account
(`ImportStagingService.stage` → `ImportAccountRepository.clearExpectFileForStagedAccount`), and
removing the file re-arm it. But that only fires on **new** `stage()` / `removeFile()` calls, and
the commit carried **no Flyway migration** to reconcile existing rows.

So if an `import_session` is already `open` with files staged when this branch is deployed, every
account that has a staged file still carries `expect_file = true` from before. The review then:

- lists those accounts under the issues panel's "still expecting a file" section;
- renders the account-map row with the "expect-file re-armed by hand — the campaign cannot commit
  until it is cleared again" badge (factually wrong — the owner never re-armed it);
- keeps the commit gate locked until the owner clears each one **by hand, one row at a time** —
  the exact toil issue 03 set out to remove (and whose bulk-clear button issue 03 also removed).

## Where to fix

A one-statement forward migration: for every `import_account` in an `open` session whose
`money_account_name` matches a live `import_file.money_account_name` in the same session, set
`expect_file = false`. Same predicate `clearExpectFileForStagedAccount` uses, applied once over the
existing data. Fold into a small `V25` (or the next import migration).

## Comments

Filed 2026-09-05 from the first `/code-review high` pass during plan f1 (review targeted the
committed issue-03 / e4 range). Pre-release caveat: the import feature is still being built on
`import/qif-plan` and there is at most one open campaign at a time, so this only matters if the
owner happens to have a campaign open across the upgrade — cheap insurance either way.
