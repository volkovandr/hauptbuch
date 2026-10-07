# Postings are edited in place, one leg per real own account

Statement reconciliation (`docs/statements.md`) links a statement line to a **posting** and marks
that posting `reconciled`. Both facts must survive the operator editing the transaction — fixing a
payee, a note, a category — or every edit would silently undo reconciliation work. They did not:
`LedgerService.editTransaction` hard-deleted every posting of the transaction and inserted fresh
ones, and no edit path carried `reconciliation` across, so every dock edit already reset every leg
to `unreconciled`, wiping the `R` marks imported from Money. We considered (a) linking a statement
line to `(transaction_id, account_id)` instead of a posting, leaving the engine alone; (b) keeping
delete-and-reinsert and having `ledger` detach and reattach the links around it; and (c) editing
postings in place. (b) needs `ledger` to reach into `statements`' table — a module cycle — or a
two-phase callback, with an FK from the link blocking the delete in between. (a) leaves posting
identity meaningless and still loses the flag. We chose **(c)**: `editTransaction` pairs the new legs
with the existing ones **by account** and updates a paired leg in place, so its `posting_id` survives;
its `reconciliation` survives when its amount is unchanged and drops to `unreconciled` when it
changed. An old leg with no partner is deleted and a new one inserted `unreconciled`.

Pairing by account is only well-defined if an account appears once per transaction, so we added the
rule that makes it so: **a transaction carries at most one posting per real own account** —
`asset`, `liability` or `equity`, person leaves excepted. Income and expense accounts and person
leaves may repeat (two receipt lines `for Max` are two postings to Max-EUR); none of them is ever on
a bank statement, so their legs fall back to delete + insert. The same rule makes the statement
match 1:1 by construction.

## Consequences

- **The rule is enforced in `LedgerService` validation**, rejecting a violation with a message rather
  than merging the legs (a merge would lose per-line notes and tags). Not a DB constraint, the same
  stance as sum-to-zero and leaves-only (data-model §8). Existing violations had to be fixed by hand
  first — the owner's production book had two.
- **`ledger` reports downgrades through an interface it owns** — the postings whose `reconciliation`
  dropped on edit, and every posting of a voided transaction — and `statements` implements it to drop
  their matches. An unpaired old leg's match goes by `on delete cascade`. This is the dependency
  inversion recurring already uses for person merges; `ledger` never learns about statements.
- **Posting ids are now stable across edits.** Anything that later references a posting (rules engine,
  learned mappings) can rely on it; before this, a reference survived only until the next edit.
- **A changed amount — or, added 2026-10-07, a changed transaction date — always costs the
  reconciled state.** Re-reconciling is the honest consequence of
  changing a figure the bank already confirmed; the statement page shows the line as no longer
  matched the next time it is opened.
