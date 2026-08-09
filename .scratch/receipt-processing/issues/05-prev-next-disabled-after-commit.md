# Prev/Next go dead the moment a receipt is committed, breaking the "commit, next" pile rhythm

Status: needs-triage
Category: bug
Severity: medium
Area: Receipts — processing screen chrome (prev/next nav, `receipt-process.html`) × commit
(`ReceiptProcessingController`/`ReceiptService`, plan §9g)

## Symptom (owner report)

After confirming (committing) a processed receipt, both the **Prev** and **Next** buttons go
disabled, and the ↑/↓ keyboard shortcuts stop doing anything too. The only way to move on is
**Back** to the register list, then double-click a different receipt — several extra clicks for
what should be a one-key "commit, then keep going" rhythm. The owner explicitly wants Prev/Next to
stay usable right after a commit, not to disable.

## What exists today

- `receipt-process.html:290-331` (the `chrome(oob)` fragment) renders Prev/Next as a real `<a
  data-receipt-prev>`/`<a data-receipt-next>` link only `th:if="${neighbours.prev() != null}"`
  (respectively `.next()`); otherwise it renders a disabled `<span class="btn is-disabled">` with no
  `data-receipt-prev`/`data-receipt-next` attribute at all.
- `keyboard.js:138-146` drives ↑/↓ by doing `clickIfPresent("[data-receipt-prev]")` /
  `clickIfPresent("[data-receipt-next]")` — so once the link is gone (replaced by the disabled
  span), both the button *and* the keyboard shortcut go dead together, matching the report.
- `ReceiptProcessingController.confirm` (`:367-382`) calls `receiptCommitService.confirm(...)`
  then `renderPane(id, null, state, range, model)`, which calls `addChrome` (`:474-485`). `addChrome`
  recomputes neighbours via `receiptService.neighbours(id, ReceiptFilters.statesFor(state), ...)`
  using the **same `state` filter param the screen was opened with** — carried through the whole
  session via the `state`/`range` hidden fields, not re-derived from the receipt's new state.
- `ReceiptService.neighbours` (`ReceiptService.java:189-199`) finds the receipt's position by
  scanning `receiptRepository.findForRegister(states, from)` — the list **filtered to `states`** —
  and returns `ReceiptNeighbours.NONE` (both null) if the receipt isn't found in that filtered list
  at all (the javadoc says as much: "A receipt no longer in the list … has no neighbours").

## Root cause

The default (and by far the most common) filter is `state=queue`, which resolves to
`ReceiptState.WORK_QUEUE` — **explicitly excluding `committed`** (`ReceiptState.java:44-48`: "A
committed receipt backs a transaction — it is not outstanding work"). The instant `confirm` moves
the receipt to `committed`, `addChrome` re-runs `neighbours` against that *same* work-queue filter
— and the receipt just committed is no longer *in* that filtered list. The lookup loop never finds
its own id, falls through to `ReceiptNeighbours.NONE`, and both Prev and Next come back null — not
because there's nothing left to review, but because the just-committed receipt can't find *itself*
in a list that was never going to contain it once committed.

This is consistent with the symptom being specific to committing (not, say, deleting or reopening)
under the default queue filter, and with the doc's own stated intent
(`receipt-process.html:186`: "the pile rhythm is 'commit, ↓, next receipt'") — the exact flow this
breaks. Browsing under `state=all` (which does include `committed`) would not hit this, since the
committed receipt stays present in that filtered list and can find its own neighbours normally —
worth confirming as part of triage whether the owner's repro was under the default queue filter.

## Comments

Filed 2026-08-09 from an owner report while testing after the issue #03 list-poll work landed —
unrelated to that change. The owner asked only for this to be documented; the root cause above was
already traced (file/line-level) during the initial look before being asked to stop and just write
it up, so a future triage pass can go straight to deciding the fix rather than re-diagnosing.

Left at `needs-triage` deliberately — the root cause is clear, but the fix approach (e.g. should
`neighbours` fall back to computing the committed receipt's position using its *pre-commit* filter
membership; should it special-case "the receipt just acted on" to always be found regardless of
filter; should committing under a filter that would hide the result auto-widen to `all` for
navigation purposes only) is a design call for the owner, not obvious enough to bake into a brief
unprompted.
