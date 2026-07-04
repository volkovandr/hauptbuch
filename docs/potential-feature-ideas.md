# Hauptbuch — Potential feature ideas

The ideas came out during implementation or testing of the Hauptbuch project.

They are not planned yet and the list of these ideas might be used to extend the backlog,
or they could be implemented between the stages.

We should check the list from time to time during the implementation because there is a possiblity
that *now* is the right time for some of them.

## Account list as balance sheet (discarded) 

**discarded** because the purpose of the account list is to show the accounts and let the user change their settings. 
This is not a finanical page, this is more like a settings page. The balance sheet is a financial page, and it should be implemented as a separate page or a report.

Three connected features:

* The account list should display all account balances
* The not-editable accounts should be displayed as well, e.g. opening balances, or the aggregated income and expenses accounts
* The total assets and total liabilities should be displayed at the bottom of the list, so that the user can see that the balance sheet is balanced.

## Display current account balances in the accounts page

Seems useful. Experience shows absence of this as an inconvenience. Showing the balance of the leaf accounts looks simple,
but implementation of the parent rollup is more complex.
