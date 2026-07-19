# Selecting a wrong-currency target account in a split breaks the register and locks the dock

Status: needs-triage
Severity: medium
Area: Transaction register

When entering a large multi-currency split transaction, selecting a `To -> <account>` that
points to an account in a different currency than the transaction currency is (correctly)
rejected: the target account must be in the same currency. But there are three problems:

1. The problem is only surfaced on Save. It would be better to tell the user immediately, when
   the account is selected, that it is not allowed.
2. When the problem appears, the transaction register table disappears. This is simply wrong.
3. After selecting the correct account, the Save button still does nothing — and no error
   appears in the logs. The same error message stays on screen and nothing works. Cancel does
   not work either; the only way out is to refresh the page.

## Comments
