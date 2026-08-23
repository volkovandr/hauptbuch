# No way to erase parts of a receipt image before analysis (marketing blocks, QR codes)

Status: needs-triage
Severity: medium
Area: Receipts — image pre-processing editor (`receipt-editor.js`, Cropper.js leaf)

> *Rough capture — filed for a proper grilling later. Nothing here is decided.*

## Request (owner)

While pre-processing a receipt image, be able to **erase parts of it** — not just crop the edges.

Use cases:

- **A long receipt with a middle band nobody needs.** Items at the top, then a huge marketing block
  full of text, then the transaction footer (time, date, account no.) at the bottom. Crop can only
  take the top *or* the bottom; the whole middle goes to the AI and is paid for in tokens.
- **A big QR code.** Nothing useful in it, and letting the model even attempt it is token waste at
  best and a security risk at worst (it encodes a URL, and the parse instructions are the only thing
  that stands between that and a prompt-injection attempt).

## Why the existing tools don't cover it

The pre-process editor today does crop, rotate/tilt, skew, brightness/contrast, and grayscale — all
whole-image or edge operations, baked client-side into the uploaded JPEG (`save()` in
`receipt-editor.js`). None of them can remove an interior region while keeping what is above *and*
below it.

## Open questions for the grilling (not answered here)

- **Erase vs. excise.** Paint the region white (image stays the same size) or cut the band out and
  splice the remainder together (image gets shorter — fewer pixels, so actually fewer tokens)?
  The QR case wants the first; the marketing-block case arguably wants the second. Possibly both,
  possibly one tool that does the vertical-band case only.
- **How many regions**, and what shape — free rectangles anywhere, or horizontal bands only (which
  is what the long-receipt case needs and is far simpler to draw and to replay)?
- **Interaction with crop.** Order of operations, and whether erase rectangles are expressed in
  original-image coordinates or post-crop ones.
- **Recipe replay.** `edit_recipe` (V10) stores the crop/tilt/skew/filter settings so a re-edit
  replays onto the original — erased regions would have to join that JSON and be replayed too,
  or a re-edit silently un-erases them.
- **Does anything else need to know?** The baked file is what every later read view serves, so an
  erased receipt is erased everywhere — including for a human looking at it after commit. Is that
  acceptable, or should the un-erased original stay viewable?

## Comments

Filed 2026-08-23 from the owner's real-use notes. Related to issue 12 (the Safari filter-bake fix)
only in that both live in the same client-side bake path.
