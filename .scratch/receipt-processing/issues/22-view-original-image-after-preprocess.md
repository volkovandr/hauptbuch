# No way to view the original scan once a receipt has been pre-processed — an over-tight crop is unrecoverable by eye

Status: resolved
Category: enhancement
Severity: medium
Area: Receipts — processing screen read view (`receipt-process.html`, `ReceiptImageController`)

> *Filed rough, then settled and built the same day — see the Comments.*

## Report (owner)

Cropped too aggressively during pre-process, and the transaction date fell outside the crop. It is
gone from the analysed image, so the AI never saw it — and it cannot be filled in by hand either,
because from that point on **there is no way to look at the original**. Clicking the image opens the
edited one, at full size, but still the edited one.

Request: a button — or a toggle that flips between **original** and **edited** — on the receipt's
image view.

## What exists today

The original is never lost: `receipt.original_path` is immutable (ARCH-07), and
`ReceiptImageController` already serves it at `/receipts/{id}/image`, alongside the baked one at
`/receipts/{id}/edited`. The gap is purely in the read view — `receipt-process.html` picks the URL
by state and hardcodes `/edited` for every state from `pre_processed` onward (the `processing`,
`processed` and `committed` blocks all point at `/edited`), so the original is unreachable from the
UI even though the bytes are right there.

Re-editing is not the workaround it looks like: the editor replays the saved `edit_recipe` onto the
original, so re-entering the editor does show the original — but only inside a crop box, and
committing to look at it means going back through Save.

## Open questions for the grilling (not answered here)

- **Button vs. toggle vs. side-by-side**, and where it lives — on the inline `<figure>`, on the
  full-size click-through, or both.
- **Which states.** `pre_processed` only (where the comparison matters most), or every state
  through `committed` (which is where the owner actually hit it — a booked receipt missing a date)?
- **What the toggle means for the full-size link** — currently a plain `<a target="_blank">`; a
  toggle implies either two links or a bit of state, and per §1.6 any JS beyond the existing
  editor leaf needs justifying.
- **Does the answer overlap with a "restore/re-edit from original" affordance?** Seeing the original
  solves *reading the date by hand*; it does not solve *the AI never saw it*. Whether an easy
  "re-edit and re-analyse" path belongs in the same change is worth deciding — it touches issue 19
  (re-parse and re-seed a processed receipt).
- **The delete path.** "Remove the image files too" deletes both paths, so a toggle must tolerate a
  missing original the same way `/edited` 404s before pre-process.

## Comments

Filed 2026-08-23 from the owner's real-use notes, same session as issue 21 (erasing regions). Both
are about the pre-process bake being a one-way door; this one about *seeing* past it, 21 about
*doing more* before it.

---

Fixed 2026-08-23 (issue/receipts-22). Two decisions settled with the owner first: the toggle goes on
**every state that has an edited image** (`pre_processed`, `processed`, `committed`, `failed` — the
report came from a *committed* receipt, so restricting it to the review screens would not have
helped), and it works as an **htmx swap with no new JS**, keeping §1.6 clean.

The three hardcoded `<figure>` blocks collapsed into one `imageFigure(receipt, showOriginal)`
fragment, and a `GET /receipts/{id}/image-view` endpoint returns it for the other variant, swapping
`outerHTML` on `#receipt-image`. The full-size click-through follows whichever image is shown. The
button renders only when `editedPath != null`, so a `new` receipt shows its original with no
pointless toggle, and a receipt whose files were deleted degrades exactly as it already did.

The transient `processing` view keeps its own greyed figure: it is a 2-second poll that
`HX-Refresh`es the whole page on completion, and there is no action to take while waiting.

Six integration tests cover it: the toggle appearing on `pre_processed` and `committed`, not
appearing on `new`, the swap to the original offering the way back, the default returning to the
edited image, and a missing receipt falling back to the register. `./gradlew check` is green.

**Not yet verified in a browser.** MockMvc proves the fragment renders and the URLs are right, not
that the swap lands — and this repo has been bitten by exactly that gap before (an htmx bug invisible
to MockMvc assertions). Worth one click on a pre-processed receipt before this is trusted.
