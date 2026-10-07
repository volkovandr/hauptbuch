# A date edit unreconciles; a shared posting is offered to every fitting line; no scroll jump

Status: resolved (slice c2)

## Symptom
Found while the owner tested c2:
1. Changing a matched transaction's **date** in the register kept its match and its `reconciled`
   mark, although the match was made on that date (within the profile's window).
2. One ledger transaction fits **two** statement lines (same amount, both in its window). Only the
   closer line offered the match, but the correct one was the other — and nothing could be done.
3. Every button on the statement page reloads it and scrolls to the top; with ~90 lines, after every
   Accept the operator must scroll back to the place they were.

## Root cause
1. data-model §3.6 / statements.md §5 listed only an amount change or a move to another account
   as dropping `reconciled`; the date was never in the list.
2. statements.md §4.4 gave a posting to exactly one line (best tier, then closest date). The date is
   a weak signal (the ledger date is the purchase date, the bank books days later), so the heuristic
   picked wrongly and hid the right answer.
3. The actions are plain form posts that redirect to the page.

## Accepted solution (owner's decisions)
1. **A changed transaction date drops every `reconciled` leg of the transaction** to `unreconciled`
   and removes their matches, like a changed amount (reported to `ledger`'s drop listeners).
   statements.md §5, data-model §3.6/§15 and ADR 0003 updated.
2. **A posting that is an exact (equal-amount, own-account) candidate of several lines is offered to
   all of them.** Such a line gets the status *competing* ("fits another line too"): it shows
   Accept, and **Accept all exact skips it**. Accepting one line matches the posting; the other
   line's candidate disappears (a posting is matched at most once per statement) and it falls to
   its next candidate or to missing. Lower tiers (amount differs, wrong account) keep the single
   best owner — the best tier still wins across lines.
3. **htmx, option 2:** the match-action and save forms post with `hx-post`, and the page's `<main>`
   is swapped in place — no scroll jump. The forms keep `method`/`action`, so the page works
   without JS. Delete still does a full navigation.

## Left as is
- The date rule applies to every `reconciled` leg of the transaction (a transfer's two legs
  included), since the transaction date is shared.
