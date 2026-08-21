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

## Receipts page

* When editing an analyzed receipt, the field widths are adjusted depending on the size of the content. This looks bad, and it is not necessary because their initial size is already good enough. 
  We should set it in the way that they do not change their sizes, but at the same time the app still looks fine on smaller screens. (Owner is exploring a CSS-only fix directly.)
