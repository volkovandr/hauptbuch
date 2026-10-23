# A late currency refresh overwrites the amount typed since it was sent

Status: resolved
Category: bug
Severity: medium
Area: Transaction register — entry dock (`entry-dock.html` `amountField`, `RegisterEntryController.currencyFields`)

## Symptom
Pick the dock's Account or Category, tab to Amount and type a number quickly: if the account /
category refresh response lands after typing has begun, the Amount field goes back to empty and the
typed figure is lost. Everything that then reads the field sees nothing — e.g. pressing `s` right
afterwards opens the split panel with a total of 0.

It surfaced as an intermittent failure of `RegisterKeyboardBrowserTest.splitShortcutOpens…` (the
remaining readout showed `-15,50` instead of `4,50`), where Playwright types instantly. On the
Raspberry Pi, with responses taking a few hundred milliseconds, a person typing right after picking
a category can hit the same window.

## Root cause
Changing the Account or Category posts `/register/currency-fields` (`hx-include="closest form"`).
The response swaps the whole `amountField` fragment out-of-band, and that fragment renders the Amount
`<input>` from `amountText` — the `amount` value *as it was when the request was sent*. A response
to a request sent while the field was still empty therefore replaces the input with an empty one,
discarding whatever was typed in between. The fragment's own comment promises the refresh works
"without resetting whatever the user has already typed"; that is only true when the typing happens
before the request is sent.

Request order from a failing run (amount typed between the first two lines):

```
POST /register/currency-fields   amount=      <- sent, field still empty
(operator types 20)
RES  /register/currency-fields                 <- stale response re-renders the input empty
POST /register/split             amount=       <- the dock now reads as empty
```

## Accepted solution (owner's decision: option 2, product fix)
The refresh only ever needs to change the Amount **label** (`Amount (EUR)` follows the category
currency). Stop re-rendering the input:

- Split the label out of `amountField` into its own fragment with an id (`entry-amount-label`), used
  by `amountField` for the normal render and returned **out-of-band on its own** by
  `currencyFieldsResponse`. The Amount input is no longer part of the response, so no stale response
  can touch it.
- Drop what that leaves unused: the `oob` flag on `amountField`, the `amountText` parameter of
  `currencyFieldsResponse`, and the matching model attribute in `currencyFields`.

Alternatives considered: `hx-preserve` on the input (rejected: a full dock reset after a commit
would then keep the old typed value too); waiting for in-flight requests in the test only (rejected:
hides the bug).

## Notes
- The Off account / Base fields, swapped by the same response into `#entry-currency-amount`, are also
  echoed from the request and have the same theoretical window. They are *meant* to follow the amount
  (suggestions, issue 27) and are left as they are; revisit only if it is seen in use.
- No change to what the server stores or books — display of the typed field only.

## Tests
- `browserTest` (new, deterministic): hold the first `currency-fields` response with a Playwright
  route, type an amount, then release the stale response — the amount must still be there. Fails
  before the fix.
- The existing `splitShortcutOpens…` test stops being flaky as a side effect; run it repeatedly.
- Integration: the currency-fields response contains the label and no `entry-amount` input; the dock
  render still has both.
