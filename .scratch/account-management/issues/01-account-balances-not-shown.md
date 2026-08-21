# Accounts page doesn't show account balances

Status: needs-triage
Severity: medium
Area: Account management (`accounts` module, account list screen)

The accounts management page lists accounts but shows no balances. Seeing a balance today means
leaving to the register and filtering by account, which is inconvenient for a quick check.

Request: show each account's current balance on the accounts list. Leaf accounts are a direct
balance query; parent accounts need a rollup (sum of descendant leaf balances), which is the more
involved half of this ticket.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
