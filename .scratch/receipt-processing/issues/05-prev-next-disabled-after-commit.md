# Prev/Next go dead the moment a receipt is committed, breaking the "commit, next" pile rhythm

Status: ready-for-agent
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

## Owner decisions (triage, 2026-08-10)

Verified against the current code: the bug is real and confirmed as diagnosed above (`confirm`
recomputes neighbours *after* the state change, against the same filter — `deleteCommitted`
already avoids this exact trap by resolving neighbours *before* acting).

1. **No filter fiddling.** The active state filter is never widened or otherwise touched for
   navigation purposes.
2. **Filter still includes `committed` (e.g. `state=all`, or a single-state filter of
   `committed` itself)** — no behavior change: the receipt stays in its own filtered list across
   the commit, so Prev/Next should (and, per the trace above, already do) keep working exactly as
   before. You can go to the next receipt and back to this one normally.
3. **Filter excludes `committed` (e.g. the default `state=queue`, or any other single-state
   filter)** — a successful commit should auto-advance, the same way deleting a receipt already
   does: the committed receipt "disappears" from the filtered list and the next receipt in that
   list becomes current automatically, with the UI updating accordingly. No manual Next click
   needed for the "commit, next" rhythm.
4. **No-next fallback** — when there's no next receipt in the filtered list, mirror the existing
   delete-then-navigate ladder exactly: land on **prev** instead; if there's neither, return to the
   register list.
5. **Accepted limitation** — this means the user cannot navigate back to a receipt they just
   committed if it's now excluded by the active filter (e.g. from the default queue view). Owner
   explicitly accepts this for now; only the delete flow's existing behavior is being mirrored, not
   extended.

## Agent Brief

**Category:** bug
**Summary:** Confirming a receipt under a filter that excludes `committed` (the default work-queue
view) strands Prev/Next in a disabled state because the neighbour lookup runs *after* the commit,
against a filtered list the receipt itself just left. Fix this by mirroring the app's own
delete-then-navigate pattern: resolve neighbours before the commit, and when the filter would hide
the freshly-committed receipt, auto-advance to the next one instead of re-rendering in place.

**Current behavior:**
- Confirming a receipt always re-renders the same pane in place (read-only), regardless of the
  active filter — it never navigates.
- The Prev/Next neighbour lookup for that in-place re-render runs *after* the commit, using the
  filter the screen was opened with. When that filter excludes `committed` (the default), the
  just-committed receipt is no longer a member of its own filtered list, so the lookup can't find
  it and returns no neighbours — both Prev and Next (and their ↑/↓ shortcuts) go dead, even though
  there may be plenty of other receipts still in the queue.
- When the active filter *does* include `committed` (e.g. `state=all`), the receipt stays a member
  of its own filtered list across the commit, so this particular failure mode doesn't occur today.

**Desired behavior:**
- On a successful commit, if the active filter's resolved state set still includes `committed`:
  unchanged — re-render the same pane in place, read-only, with working Prev/Next exactly as
  before.
- On a successful commit, if the active filter's resolved state set excludes `committed`: treat it
  like the receipt just left the list. Resolve its neighbours from that filtered list as it stood
  *before* the commit (the receipt is still a member at that point — same timing the existing
  delete flow already uses), then automatically land on the next receipt in that list; if there is
  no next, land on the previous one; if there is neither, return to the register list. The carried
  `state`/`range` filter parameters are preserved across this navigation, and the resulting URL
  matches whatever receipt is now being viewed (so a reload or bookmark lands correctly — this
  can't be a client-side-only fragment swap that leaves the address bar pointed at the
  just-committed receipt).
- A commit refused by the hard-blocking gate (the confirm-problems path) must never navigate — the
  same pane stays in place, editable, listing the block reasons, exactly as today.
- Reopen is untouched by this change.

**Key interfaces (durable names — locate them, don't trust line numbers):**
- The receipt-confirm handler (`POST /receipts/{id}/confirm`, on the receipt processing
  controller) — needs the branch described above between "stay in place" and "auto-advance."
- `ReceiptService.neighbours` — already the right primitive; call it before the commit happens
  when auto-advance may apply, the same way the existing committed-delete handler already does.
- The existing delete flow's "land on next, else prev, else register" landing helper — reuse it
  (or generalize its name if it now serves more than delete) rather than re-implementing the same
  fallback ladder.
- Whatever resolves a filter token to its underlying state set (used elsewhere to turn `state=all`
  vs `state=queue` vs a single named state into the actual list of states) — use it to decide
  whether `committed` is in the active filter's resolved set.
- The mechanism needed to force the browser to a different receipt's URL from what is otherwise an
  in-place htmx pane swap (Confirm's current wiring targets and swaps the pane fragment directly).
  Prev/Next themselves already navigate via plain links to `/receipts/{id}`; the auto-advance case
  needs the equivalent full navigation to fire as a result of the Confirm request succeeding.

**Acceptance criteria:**
- [ ] Committing a receipt while the active filter's resolved states include `committed` (e.g.
      `state=all`) shows no behavior change: the pane re-renders in place read-only, and Prev/Next
      keep working normally (can navigate to the next receipt, then back to this one).
- [ ] Committing a receipt while the active filter's resolved states exclude `committed` (e.g. the
      default `state=queue`) automatically advances to the next receipt in that filtered list on a
      successful commit — no manual click needed to continue the "commit, next" rhythm, and ↑/↓
      keep working on the newly-current receipt.
- [ ] When there's no next receipt in that filtered list, the fallback lands on the previous
      receipt in the list; when there's neither, it returns to the register list — matching the
      existing delete flow's fallback ladder exactly.
- [ ] The neighbour lookup driving auto-advance uses the filtered list as it stood *before* the
      commit, not after.
- [ ] A commit refused by the hard-blocking gate never navigates — same pane, same editable state,
      block reasons still listed, exactly as today.
- [ ] After an auto-advance, the browser's URL reflects the newly-current receipt (reload/bookmark
      consistency) — this is not solely a client-side fragment swap.
- [ ] The carried `state`/`range` filter parameters are unchanged across the auto-advance
      navigation.
- [ ] Reopen's behavior is unaffected.
- [ ] Test coverage (integration tier, per CLAUDE.md §6 — this is rendered controller/htmx
      navigation behavior, not pure SQL logic) for: filter includes committed → no navigation;
      filter excludes committed with a next available → lands on next; filter excludes committed
      with no next but a prev available → lands on prev; filter excludes committed with neither →
      back to the register list; refused commit → no navigation regardless of filter.
- [ ] `./gradlew check` green.

**Out of scope:**
- Changing Reopen's navigation behavior.
- Any change to how Prev/Next behave when clicked manually — this is only about what happens
  automatically immediately after a successful Confirm.
- Restoring the ability to navigate back to a receipt that was just committed and is now excluded
  by the active filter — an accepted, explicit limitation for now (owner decision above).
- Widening or otherwise altering the active state filter itself for navigation purposes.

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

> *This was generated by AI during triage.*

Triaged 2026-08-10: root cause confirmed against current code. Owner decided against any filter
widening — instead, a successful commit under a filter that excludes `committed` should
auto-advance to the next receipt (falling back to prev, then the register list), mirroring the
existing delete-then-navigate pattern exactly; no change when the filter already includes
`committed`. Moved to `ready-for-agent` with the brief above.
