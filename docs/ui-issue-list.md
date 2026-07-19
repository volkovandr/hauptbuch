# ui-issue-list

This document contains a list of UI issues that were discovered during the testing and using the application.
We should look through this list from time to time and see if we can fix issues during the current development cycle.

This is different from the `docs/potential-feature-ideas.md` document, which contains ideas for new features or improvements.
Current document contains issues that block the usage or make it inconvenient, and we should fix them as soon as possible.

After the fix the issue should be removed from the list.

## UI Issues

### Transaction register

* Minor issue: When selecting a Tag from the dropdown, you should press Enter after selecting once again, because
  right after the selection it is still in the "editing" state. I prefer to have the Tag selected and confirmed
  right after picking it from the dropdown, wither with mouse or keyboard, and only when I type its name Enter should be necessary.
* Minor: When selecting a transaction for editing, depending on the transaction features (e.g. currencies of the legs,
  tags, notes) the buttons: Save, Void, Cancel appear in different places. This is confusing and annoying.
  They should always appear on the same place, e.g. at the "new row" relatively to the other controls in the dock, on the right side of the dock.
* Medium: The Category selector allows selecting a non-leaf categories without saying anytning. When I press Save nothing happens as if the button is not working. 
  But in the logs i see the error about non-leaf account. The Category selector should not allow selecting non-leaf categories, or at least make the user aware of the issue.
* Minor: the order of fields in simple transaction is different from the order of fields in the main part of a split transaction.
  This is a bit confusing and inconsistent. The order of fields should be the same in both cases.
  My preferred order is: Date, Account, Currency, Payee, Total (in account currency), Off account (in transaction currency, optional), Base (in base currency, optional), Category, Tags, Notes. When we press
  the split button the "Category" picker disappears, but the positions of the other fields should not change.
* Medium: When entering a big complicated multi-currency split transaction I selected in one of the lines a To-><some account> that was pointing to an account in different currency than the transaction
  currency. The system told me this is wrong because the target account must be in the same currency (makes sense). There are three issues:
  - I only see there is a problem when I press Save. It would be better to have the system tell me immediately when I select the account, that it is not allowed.
  - When such problem appears the transaction register table disappears. Why? This is simply wrong.
  - After I selected the correct account, the Save button did not work. And there was no error in the logs. The same error message still appeared on the screen and nothing worked. In fact, the only way out of this
    is to refresh the page. Cancel does not work, none of the buttons work. 