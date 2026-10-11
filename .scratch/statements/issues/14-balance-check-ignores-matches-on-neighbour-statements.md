# Balance checks: a posting matched on a neighbouring statement leaves an unexplained difference

Status: resolved

## Symptom
Two consecutive statements of one account. A transaction dated in September is booked by the bank
in August and matched on the August statement (which goes green). The September statement then
shows an opening difference equal to that amount, unexplained. Unmatching it on August makes it an
extra on September (green there) but leaves an unmatched line on August.

## Root cause
statements.md §6.2 explains a difference only by postings matched to *this* statement and by
boundary extras (unreconciled legs). A reconciled leg matched to a *neighbouring* statement is
neither, yet it sits on opposite sides of the ledger date and the bank date at this statement's
boundary. It is correctly not an extra (§6.3 excludes reconciled legs).

## Accepted solution (owner, 2026-10-11)
Reconciled postings count. Per matched posting of the account, on any live statement, the explained
part at a boundary is (bank has it) − (ledger has it) times its amount: the bank has it at the
opening when the matching statement ends before this period starts, at the closing when it ends on
or before this period's end; the ledger has it when the posting is dated before the opening /
on or before the closing. This subsumes the existing "matched here, dated outside" term. Boundary
extras are unchanged.

Also in this change: the list column "Green" is renamed "Match" and centred; the statement page
gets a "Back to statements" button beside the title.
