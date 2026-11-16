# Statements: add and remove statement lines without unmatching the whole statement

Status: open
Category: improvement
Area: statement page line grid (`StatementController` / `StatementService.updateLines`), `statement_line`
Design: `docs/statements.md` §3.2 (Re-seed, line grid), §3.2a (Parse), matches and `reconciled` (unmatch rules)

## Symptom
A PDF is not always parsed 100% correctly, and the owner often notices only after matching
everything: one or two lines are **missing** (or, less often, spurious). The original PDF shows
them, but there is no good way to get them into the statement:

- the line grid edits the **existing** lines only — it cannot add or remove a line;
- the TOON (`parse_raw`) can be edited and re-seeded, but Re-seed and Parse are **refused while any
  line is matched**, so the owner must unmatch the whole statement, edit the TOON, re-seed, and
  match everything again;
- the only other route is inserting into the DB by hand.

Both workable routes are bad for a statement that is 99% matched.

## Root cause
The statement's lines can only be *replaced wholesale* (Re-seed / Parse, which require nothing
matched) or *edited in place* (the grid, which has no add/remove). There is no per-line add or
remove, so fixing one line forces a statement-wide reset.

## Proposed solution
In the statement UI's line grid:

- **Add a line**: an empty row (booking date, value date, amount, counterparty, description, bank
  category — the fields the grid edits). Booking date and amount are required. The new line is
  **unmatched**, is appended after the last line (`sort_order` = max + 1), and appears in the
  missing/unmatched counts at once. It may be added while other lines are matched.
- **Remove a line**: a per-row remove action. Removing an **unmatched** line just deletes it.
  Removing a **matched** line first unmatches it, with the same effect as the existing *Unmatch*
  (posting back to `unreconciled` unless another statement still matches it), and the page says so
  beside the action, as it does for editing a matched line's date or amount.
- **Change a line**: already possible in the grid (a matched line's booking date or amount drops its
  match). Unchanged.

The statement's computed figures (expected vs. actual closing balance, the explained difference)
recompute from the lines as they already do; no stored total changes.

## Owner's decisions
- A manually added line is **not marked or treated specially** — it is an ordinary `statement_line`,
  indistinguishable from a parsed one.
- Removing a line is a **hard delete** (after unmatching it and clearing its
  `statement_line_exclusion` rows). Only whole statements are soft-deleted (`statement.deleted_at`);
  `statement_line` has no `deleted_at` and does not get one.
- **Per-row actions, not the grid's Save**, whichever is technically simpler: an *Add line* action
  and a *Remove* button per row, each its own htmx request. One extra click now and then is fine.

## Tests (outline)
- unit (`StatementService`): add inserts an unmatched line; remove of a matched line unmatches
  first; invalid input (unparseable date/amount) saves nothing.
- `integrationTest`: repository insert/delete round-trips (incl. dependent `statement_match` and
  `statement_line_exclusion` rows); controller acceptance for add and remove on a statement that
  has matched lines.

## Comments
