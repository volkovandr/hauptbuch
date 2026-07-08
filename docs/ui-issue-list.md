# ui-issue-list

This document contains a list of UI issues that were discovered during the testing and using the application.
We should look through this list from time to time and see if we can fix issues during the current development cycle.

This is different from the `docs/potential-feature-ideas.md` document, which contains ideas for new features or improvements.
Current document contains issues that block the usage or make it inconvenient, and we should fix them as soon as possible.

After the fix the issue should be removed from the list.

## UI Issues

### REGISTER page

* When creating a new transaction and selecting a Payee, the most used catagory should be pre-selected automatically.
  but this is not working as expected: When my Food category has two children: Sweets and Non-Sweets, and I only use
  Sweets for a given payee, when I select the payee, the Food (the parent category) is selected, instead of Sweets (the child category).
  This is wrong, and since Food cannot be selected for a transaction, the user has to select Sweets manually. This is inconvenient and should be fixed.