# `expect-file` should be driven by file presence, not a manual click

Status: resolved
Category: bug
Severity: medium
Area: Import review — account map / `expect-file` (`ImportStagingService`, `ImportAccountMapService`, `ImportAccountRepository`)

## What happens now

`import_account.expect_file` defaults to `true` for every newly-discovered Money account name
(the file's own account and every transfer counterparty alike) and is cleared **only** by the
owner clicking "Stop expecting a file" on that row in the account map — staging a file for the
account does not clear it (plan c2's deliberate decision, "a staged file does not auto-clear it").

This surfaced as confusing during the e4 review: an owner staged files for two accounts and the
review kept listing both as "still expecting a file" despite the export being right there. The
immediate fix (commit `db51135`) added a bulk "Stop expecting a file for every account whose
export is already staged" button to the issues panel — a workaround for the symptom, not the
actual gap.

## What should happen (owner's spec, 2026-09-05)

Money never exports partial account history — an export is always the whole account as of that
moment (confirmed by the owner via direct testing: re-exporting after a week of new activity
yields a file containing the old transactions too, so a re-import must either replace the prior
file's rows or accept duplicates; there is no partial-export concept in Money at all). Given that,
staging **any** file that names an account as its own is conclusive: that account's data has been
provided, full stop. There is nothing left to "expect" for it.

Concretely:

1. Upload file "1" for account A. It references transfers to B and C. Account rows for A, B, C are
   created; A, B and C all require a mapping before the campaign can proceed. A already has its
   file; B and C are (rightly) still expecting one, since only A's file has been staged so far.
2. Upload file "2" for account B. **B's expect-file should clear automatically at this point** —
   the system already knows this from `import_file.money_account_name`; no click needed.
3. Mapping A/B/C to their Hauptbuch accounts does not touch expect-file — orthogonal, as today.
4. C never gets its own file, so the owner clicks "Stop expecting a file" for C by hand — this is
   the **one** legitimate use of the manual toggle: a counterparty whose export will never arrive.

Expect-file should go back to `true` in exactly two cases, both already partly wired:

- the owner manually clicks "Expect a file" after having previously cleared it (the existing
  reverse toggle — keep as-is), and
- **the owner removes the staged file** for that account from the campaign screen — the data that
  justified clearing it is no longer there, so the flag should re-arm.

With this in place, the bulk-clear button becomes unnecessary and should be removed — it exists
only to compensate for expect-file not tracking file presence in the first place.

## Where to fix

- `ImportStagingService.stage()` — after staging, clear `expect_file` for `upload.moneyAccountName()`
  specifically (the file's own account name, not every referenced name — B and C in the example
  above must **not** be affected just because A's file happens to transfer to them). Likely a new
  `ImportAccountRepository` method (or extending `upsertUnmapped`'s insert/upsert) rather than
  reusing the existing per-row `setExpectFile(long importAccountId, boolean)`, since at this point
  the row is addressed by `(sessionId, moneyAccountName)`, not yet by its id.
- `ImportStagingService.removeFile(long)` / `removeFilesNamed(String)` — when the removed file(s)
  named an account as its own, re-set `expect_file = true` for that Money account name, **but only
  when no other staged file in the session still names it**. Accounts can in principle accumulate
  more than one staged file (the per-account statistics already fold multiple files sharing a
  Money account name together) — don't assume a 1:1 file-to-account relationship when deciding
  whether to re-arm the flag.
- Remove the bulk-clear escape hatch added in `db51135`: the
  `/import/review/accounts/clear-expect-file` endpoint, `ImportAccountMapService
  #clearExpectFileForProvidedFiles`, `ImportAccountRepository#clearExpectFileForProvidedFiles`, the
  button in `import-review.html`, and their tests.
- The manual toggle itself (`ImportAccountMapService#setExpectFile`, the "Stop expecting a file" /
  "Expect a file" buttons) stays exactly as it is — it remains the only way to declare "this
  counterparty will never get a file" (clearing it) or to override an auto-clear/auto-reset by hand
  in either direction.

## Docs to correct

- `docs/import.md` §5.1's `expect-file` paragraph needs to describe the actual lifecycle: defaults
  `true` on first reference → auto-cleared when a staged file names that account as its own →
  auto-reset to `true` if that file is later removed and no other staged file still covers the
  name → also toggleable by hand in either direction (the "will never get a file" case). It
  currently only documents the manual toggle.
- `docs/implementation-plan-import.md` — plan c2's decision record ("a staged file does not
  auto-clear it... kept out of scope") is superseded by this issue; c2's entry and the e4 v0.22/v0.23
  changelog entries describing the bulk-clear button should get a follow-up note once this ships,
  since that button is being removed.

## Investigation notes (so the fix doesn't have to re-derive this)

Neither the same-currency mirror matching (`ImportMirrorRepository`'s `MATCHED_PAIRS`) nor the
automatic cross-currency resolution (`RESOLVABLE_CROSS_CURRENCY_PAIRS`) reads `expect_file` at
all — matching already re-runs on every staged file regardless of the flag's value, so nothing
about matching correctness depends on keeping it manual. The flag's only functional consumers are:

1. `ImportMirrorRepository#closeParkWithFarAmount`'s guard (the hand-entered far amount escape
   hatch, §6.4) — refuses while the counterparty's `expect_file` is `true`.
2. The e4 commit gate (`ImportIssues#locked()`) — "no referenced account still expect-file".

So auto-clearing on file arrival cannot regress matching; it only changes when (1) becomes
available and when (2) considers an account settled — which is exactly the intended effect.

## Comments

Filed 2026-09-05 during the e4 review. The owner reset the session to work this as its own package
rather than folding it into the e4 branch further.

Resolved 2026-09-05. `ImportStagingService.stage` clears `expect-file` for the staged file's own
account (`ImportAccountRepository#clearExpectFileForStagedAccount`); `removeFile` and
`removeFilesNamed` re-arm it when no other staged file still names the account
(`#rearmExpectFileWhenNoStagedFile`, `removeFilesNamed` reading the affected files via new
`ImportFileRepository#findBySessionAndFilename`). The bulk-clear button and its
endpoint/service/repo method (`clearExpectFileForProvidedFiles`, `POST
/import/review/accounts/clear-expect-file`) are removed; the per-row manual toggle is unchanged.
Docs updated: `import.md` §5.1, `implementation-plan-import.md` (c2 decision record, e4 section,
changelog v0.24). `./gradlew check` green.
