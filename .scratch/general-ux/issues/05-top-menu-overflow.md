# Top menu is too wide — move rarely used items into a `⋯` menu

Status: resolved
Category: enhancement
Severity: low
Area: General UX — top navigation

The top menu has nine items (Register, Accounts, Categories, People, Recurring, Receipts, Import,
Reports, Settings) and bank statement reconciliation adds a tenth (Statements). It no longer fits
comfortably.

## Accepted solution (owner, 2026-10-04 — statements grilling, `docs/statements.md` §8)

- Top level, in this order: **Register, Receipts, Statements, Reports, Recurring, People**.
- A trailing **`⋯`** entry opens a menu holding **Accounts, Categories, Import, Settings**.
- Built as a native `<details>`/`<summary>` dropdown styled with CSS — **no new JS leaf**
  (CLAUDE.md §1.6). It must work with the keyboard and close the way a `<details>` does.
- When the current page is one of the overflow items, the `⋯` entry carries the "current" style.
- Ships as slice **a** of `docs/implementation-plan-statements.md`. The Statements item itself is
  added by slice b1, together with its page — never a link to nothing.

## Comments
