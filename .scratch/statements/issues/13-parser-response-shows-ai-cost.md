# Statement page: show the AI cost in the Parser response block

Status: resolved

## Symptom
The statement stores the billed tokens and frozen cost of its parse (`tokens_*`, `parse_cost`) but
the page never shows them; receipts do.

## Accepted solution (owner, 2026-10-10)
Show tokens in / out / cache write / cache read and the USD cost at the top of the collapsible
"Parser response" block, as the receipt page does. Read-only; a Re-seed keeps the original usage.
