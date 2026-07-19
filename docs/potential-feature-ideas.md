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

## Account management UX improvements

* Grandchild indentation should be improved. Basically we only have parent-child visual difference. Grandchildren
should be indented more, but currently they appear on the same level as children.


## Category management UX improvements 

* Child category creation should be possible with a button next to the parent category, instead of picking a parent from a dropdown.
When such button is clicked, the only thing user would need to do is to enter the name. The new category would take the parent
from the button, and the type (expense or income) from the parent category as well.
* Type of the new category should be automatically set to the type of the parent category when a non-top-level category is created.
* Collapse-expand parent categories in the category list - a nice UX improvement. 

## Register page UX improvements

* the date range filter should provide quick selection of common ranges, like "Last 12 months", "This year", "This month".
* Ideally the edit feature should be triggered not by a small button, but by simply clicking on the transaction row. This
  is how Money works.
* Creating a new category inline works. But we need a confirmation, maybe user did not want to create a new category, but just made a typo.
  Another motivation, if I type "Food - Milk" it creates a subcategory under food, which is correct. But what if I wanted
  to create a new category called "Food - Milk" and not a subcategory? I would need to go to the category management
  which is fine, but it should be clear what the inline creation is going to do, that's why a confirmation is needed.
* Top-level categories creation inline should be possible, but it should be clear whether that is an income or expense category.
  Maybe typing something like "Income - Found on the street" should trigger creation of a new top-level income category?
* When created a new transaction and want to create another one, the form should pre-select the same date and account as the previously created transaction.
  Two reasons: most transactions a made with the same account, and purely statistically one account will appear much more often, but it is unlikely that
  it would appear the first one alphabetically. Secondly, when I have a bunch of receipts in my wallet, they usually are from the same day, or close to each other.
* The register should get another column - Base amount (in base currency), displaying it should be optional.
* The currency of the expense/income should be pre-selected based on the Payee. Usually we pay in a given shop in the same currency.
* The category selector should present the categories in a tree-like view (with indentation) so it is clear which subcategory
  belongs to which parent category. Since parent categories cannot be used for transactions, they should be displayed in 
  grey color (or not displayed at all). Currently the selector allows picking them, and pressing "Save" causes exceptions
  in the backend and no error on the frontend. This is actually an issue.
* When anything is wrong and I press Add it displays the error message, but the table gets emtptied and the form is cleared. I would prefer to fix whatever was wrong and press Add again.
  And why the whole table get cleared? That is actually a bug.
* When editing the transfer transaction the account is always pre-selected to the source account, event if I pressed Edit on the destination account. This is fine, but it feels more consistent
  to pre-fill the edit form with the account of the line I pressed Edit on.
* There is an annoying problem that on small screen the register table consumes the whole space. One should scroll down to see the Add/Edit dock. And in case
  of a split transaction, when the dock increases its size, one needs to scroll down again and again for each new line. This is very annoying.
  we should either make sure the dock is always visible (automatic scroll?) or to move the dock to the top of the page.
* The "tags" entry block has rounded corders, while all other edit blocks have square corners. It also is slightly smaller than the other blocks. We should make them all consistent.
* When editing a simple transaction it is impossible to turn it into a split transaction, although logically it should be possible. The user should not have an impression that simple
  transactions and split transactions are two different world.

## Per-person debts (deferred from stage 8)

Stage 8 ships single- and multi-person attribution, the register display, the People page, per-currency
settle-up, and merge. These were consciously left out:

* **Split-method calculator (equal / shares / exact)** — FR-DEBT-02's parenthetical. Multi-person
  attribution already works via **manual** split amounts (`for Max` / `by Max` lines with "the rest"
  defaulting); this would add a "split N ways" helper that *fills* the per-line amounts (equal, by
  shares like `2:1:1`, or exact) with remainder-penny distribution. Pure convenience over an existing
  capability — may never be built.
* **Per-group "simplify debts" suggestions** — FR-DEBT-10. Net across people to minimise the number of
  settle-up transactions.
* **Groups / trips** — FR-DEBT-07. Optional grouping of shared expenses, integrated with tags.
* **Debts over MCP** — expose the people/debts read + settle operations through the MCP server (with
  the rest of the MCP surface, a later stage).

## General UX improvements

* In the various pages instead of "Back to..." links we should have a "Cancel" button. This makes the UX more consistent.
* Modal dialogs should close on Enter accepting the changes, and on Esc discarding the changes, as if Save or Cancel buttons were clicked.
* We need a payee editor page, where the user could create (not much sense)/edit (necessary)/merge (very necessary) or delete payees.
* The difference between "zebra" light and dark colors is too big, bigger than the difference between different hue colors. 
  This makes the register look a bit confusing
* When entering a new transaction, and in fact in other places as well, the TAB key moves the selection between the date
  components, i.e. day→month→year. But in fact this is annoying, simple Right key does the same. I would like TAB to move to 
  the next field.
