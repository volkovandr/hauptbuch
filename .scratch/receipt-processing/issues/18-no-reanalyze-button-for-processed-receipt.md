# A `processed` receipt can't be sent back through analysis again

Status: needs-triage
Severity: medium
Area: Receipts — processing screen (`processed` state)

Once a receipt has been analysed (state `processed`), there's no way to discard the AI's draft and
return it to `pre_processed` for a fresh Analyse — e.g. after editing the AI note, or fixing the
crop, when hand-editing the draft in post-process isn't the more efficient fix. This is distinct
from **Reopen**/**Re-enter** (`committed` receipts only) and from **Discard edits** in the
`pre_processed` view (image edits only, no analysis draft yet to discard).

Request: a "revert analysis" action next to Save on the post-process screen that discards the
current draft lines and returns the receipt to `pre_processed`, ready for another Analyse.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
