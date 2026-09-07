# Settle-up account selector offers parent (non-leaf) accounts and has no indentation

Status: needs-triage
Category: bug
Severity: medium
Area: Settle up (People page → "Settle") — `SettleUpService.pickableAccounts()`, `settle-up.html`

## The problem

Reported by the owner while testing transaction-register-ui/22:

> When I was using the Settle feature on the People page the account selector allows selecting
> parent accounts (non-leaves). This is wrong and should not work. And that selector does not have
> any indentation, so it is hard to tell which account is a parent and which is a leaf.

Two defects in the funding-account `<select>` on the settle-up screen:

1. **Parents are offered.** `SettleUpService.pickableAccounts()` returns
   `accountService.findLiveByTypes(OWN_ACCOUNT_TYPES)` filtered only to open + non-person — it does
   **not** filter to posting leaves. A parent account (one with real children) appears as an option;
   picking it would produce a transfer whose funding leg is a non-leaf, which the engine rejects
   (leaves-only, data-model §5) — a broken Save.

2. **No indentation.** `settle-up.html` renders the options as a flat `<option th:each="a :
   ${view.accounts()}">` list, so with hierarchy in use a child is indistinguishable from a
   top-level account.

## Expected

- The selector offers only **postable leaves** among the open non-person own accounts — the same
  "real children" test `CategoryService` / `AccountService.findPostableLeafPaths` already use.
- Options convey hierarchy — either a leading-space / `— ` indent by depth in the `<option>` text
  (a `<select>` can't do real indentation), or the `Parent - Child` path idiom the register's
  Category datalist uses.

## Notes

- `AccountService.findPostableLeafPaths(types, separator)` already returns open leaves with a
  composed `Parent - Child` path and excludes real parents + currency leaves — a likely fit, though
  it is currently income/expense-oriented; check it works for asset/liability.
- The register dock's Account datalist (`RegisterService.accountOptions` → post-to set) has the same
  "leaves + hierarchy" need; keep the two consistent.
- `SettleUpServiceTest` and `SettleUpScreenIntegrationTest` exist.

## Comments

Filed 2026-09-07 from the owner during testing of transaction-register-ui/22.
