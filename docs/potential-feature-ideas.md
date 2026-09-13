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

## Type-block subtotals in reports (Balance sheet, Category matrix, This month vs last)

Noticed while reviewing reporting stage a/b: the Balance sheet Preset's rows (Account dimension)
and the Category-matrix/This-month-vs-last Presets' rows (Category dimension) are ordered purely
alphabetically by name — there is no grouping by account `type`, so assets and liabilities (or
income and expense categories) can interleave rather than appearing as separate blocks.

Desired: separate assets from liabilities in the Balance sheet, and income from expense in the
other reports, each as its own block — with a subtotal row per block where that makes sense (e.g.
"Total assets", "Total liabilities"; possibly "Total income", "Total expenses"). Whether a subtotal
makes sense varies by report/block and needs a decision per case, not one blanket rule.

This is a **distinct feature** from the parent/child category subtotal already planned for
reporting stage e ("Expansion and nesting" — a parent category row summing its own children). This
idea is about grouping and summing whole **type blocks** of otherwise-unrelated top-level rows, and
isn't covered by any current reporting stage.
