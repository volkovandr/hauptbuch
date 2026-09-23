# Closing balance with a Tag or Payee dimension is enterable in the settings strip, then fails with the generic error toast

Status: needs-triage
Category: bug
Severity: medium
Area: Reporting (`analytics` module: `ReportSettingsView`, `ReportEngine.validateClosingBalanceHasBalance`, `PresetRendering.populate`)

## What happens

On a Report's page, the settings strip lets the operator combine a **Closing balance** measure with
**Tag** or **Payee** as a dimension, on rows, columns or series, as the outer or the nested slot.
Either order triggers it: picking Tag while Closing balance is ticked, or ticking Closing balance
while Tag is on an axis. The re-render then fails:

```
ERROR GlobalHtmxErrorAdvice : Unhandled exception during htmx request GET /reports/6
java.lang.UnsupportedOperationException: A tag has no closing balance (it is not an account).
    at ReportEngine.rejectIfBalanceless(ReportEngine.java:364)
    at ReportEngine.validateClosingBalanceHasBalance(ReportEngine.java:359)
    at ReportEngine.render(ReportEngine.java:85)
    at PresetRendering.populate(PresetRendering.java:117)
    at PresetRendering.renderOwnPage(PresetRendering.java:169)
    at SavedReportController.show(SavedReportController.java:72)
```

The operator gets "Something went wrong and your change was not saved", with no hint about which
choice caused it. Owner hit this on 2026-09-23 without knowing which click did it.

## Cause

The engine's rule is correct: a tag and a payee hold no balance (`reporting.md` §4, §5). But
`ReportSettingsView` only filters the dropdowns for the cross-axis rule
(`validateOneNonDateDimension`, see the comment near line 187). The measure/dimension rule has no
counterpart in the form, so the illegal pair reaches the engine as an exception.

## Expected

Per `reporting.md` §11a.3, a structural illegal combination is **unenterable by the form's shape**:

- With Closing balance ticked, Tag and Payee don't appear in the axis dropdowns (as with the
  cross-axis rule). Or, with Tag or Payee on an axis, the Closing balance row of the measure grid
  renders disabled. Pick one direction so the operator can always get out, or both if it stays clear
  which choice to undo.
- As a safety net, the page should never 500 on a spec the engine refuses. Render the refusal
  message in place of the report, as the pie refusal (§7.4) and the scope-mismatch message already
  do, rather than letting `UnsupportedOperationException` reach `GlobalHtmxErrorAdvice`. That also
  covers any other engine refusal the form forgets to prevent (see issue 15 for the full list of
  rules).

## Tests

Unit: `ReportSettingsView` drops Tag/Payee from the axis options when a closing-balance measure is
ticked (and/or disables the measure row). Integration: `GET /reports/{id}` with a Tag dimension plus
a closing-balance measure in the query string renders the refusal message with a 200, not the error
toast.

## Comments

Filed 2026-09-23 from an owner-supplied stack trace. Related:
`15-rethink-which-dimension-combinations-are-allowed.md` (rule 7 there).
