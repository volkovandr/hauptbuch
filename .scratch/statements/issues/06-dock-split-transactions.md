# Statement dock: split transactions

Status: open (not scheduled)

## Symptom
The statement page's dock books a single-category transaction only. Rare split transactions that do not
come from receipts cannot be created from a missing line.

## Accepted direction
Reuse the register's split panel (`RegisterSplitController`, `SplitFormBinder`) from the statement
dock; the funding leg stays pinned to the bank line's signed amount. Sized as its own stage after d2.
