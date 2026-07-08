# ui-issue-list

This document contains a list of UI issues that were discovered during the testing and using the application.
We should look through this list from time to time and see if we can fix issues during the current development cycle.

This is different from the `docs/potential-feature-ideas.md` document, which contains ideas for new features or improvements.
Current document contains issues that block the usage or make it inconvenient, and we should fix them as soon as possible.

After the fix the issue should be removed from the list.

## UI Issues

### REGISTER page

* Keyboard-first entry is still shallow. `n` now jumps focus to the dock's first field, and Tab
   walks the fields with Enter to Add — but the full per-field key map from the mock-ups
   (`1 Date → 2 Account → 3 Payee → 4 Amount → 5 Category → 6 Tags → ↵ Add`, `S` to split, the
   picker open/close transitions) is not built yet. It grows as the later stage-7 sub-stages land
   (Q-UI-2 decided piecewise); revisit if the current level is too thin in daily use.