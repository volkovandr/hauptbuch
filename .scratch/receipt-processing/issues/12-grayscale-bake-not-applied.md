# Grayscale/brightness/contrast bake silently no-ops on Safari (no `CanvasRenderingContext2D.filter` support)

Status: resolved
Category: bug
Severity: medium
Area: Receipts — image pre-processing editor (`receipt-editor.js`, Cropper.js leaf)

Found during first real-conditions use after the Pi deployment (2026-08-18): "the 'make black and
white' checkbox stopped working. It only changes the preview black and white, but the final image
is always in color."

## What exists today

Grayscale is not a preview-only CSS effect — it is baked entirely client-side, in
`receipt-editor.js`:

- `filterString()` builds a CSS filter string (`grayscale(...) brightness(...) contrast(...)`) from
  the checkbox + sliders; `applyFilterPreview()` sets it as a CSS custom property for the live
  on-screen preview (plain CSS `filter` on a DOM element — universally supported).
- `save()` redraws the cropped image into a fresh `<canvas>` and sets `ctx.filter =
  filterString()` **before** `ctx.drawImage(...)` — the same filter string the preview used is
  meant to apply to the actual pixels exported via `toBlob(..., "image/jpeg", 0.9)` and uploaded as
  the edited file.
- The read view for `pre_processed`/later states always serves that same baked file back, never the
  pre-edit original — so this isn't a case of the app displaying the wrong file after save.
- The checkbox defaults to `checked` in the markup and Reset also sets it `checked` — an "off by
  default" mixup isn't the explanation.

## Root cause — confirmed, not just hypothesized

Checked current browser support (caniuse, 2026-08-18): **Safari has never shipped
`CanvasRenderingContext2D.filter`, on desktop or iOS, in any released version.** Even Safari 18+
ships it only behind a disabled-by-default experimental flag — not available to a normal user. This
is unlike the plain CSS `filter` property (which Safari supports fine on ordinary DOM elements,
which is why the live preview looks correct) — the *canvas-context* variant of the API is simply
absent in Safari. Chrome/Firefox have supported it for years, matching the owner's own repro (works
in Chrome).

Setting an unsupported value on `ctx.filter` does not throw — it's silently ignored — so on Safari
`save()` draws the cropped image through with **no adjustment applied at all**: not just grayscale,
but brightness and contrast are silently dropped too, while the preview (a different, DOM-CSS
codepath) keeps showing them correctly. This is the same browser (Safari) implicated in issue 13's
datalist-styling bug, but a different and more clear-cut root cause: a real, permanent Safari
feature gap, not a styling/appearance quirk.

No automated test coverage exists for `receipt-editor.js` (client-side-only leaf, per CLAUDE.md
§1.6 — no JS test tier in this repo).

## Fix path — no architecture conflict

Unlike issue 13, this fix stays entirely inside the existing sanctioned JS leaf
(`receipt-editor.js`) — no new leaf, no CLAUDE.md §1.6 question. Replace reliance on
`CanvasRenderingContext2D.filter` with a manual pixel-level implementation of the same three CSS
Filter Effects formulas (W3C spec — these are simple, standardized per-channel linear formulas, not
an approximation of what the native filter does):

- **grayscale(1):** `R' = G' = B' = 0.2126·R + 0.7152·G + 0.0722·B` (Rec. 709 luma weights)
- **brightness(x):** `R' = R × x` per channel
- **contrast(x):** `R' = (R − 128) × x + 128` per channel

Applied via `getImageData`/`putImageData`, in the same order the current filter string already
encodes (grayscale → brightness → contrast), this reproduces the same math the native filter
performs in Chrome/Firefox — expected to be visually indistinguishable from today's Chrome output,
not merely "close enough." `getImageData`/`putImageData` are universally supported (unlike the
context filter), including on Safari.

## Agent Brief

**Category:** bug
**Summary:** Replace `CanvasRenderingContext2D.filter` in the receipt image editor's save/bake step
with a manual per-pixel implementation of grayscale/brightness/contrast, since Safari does not
support the canvas-context filter API at all (confirmed via caniuse, 2026-08-18) and silently drops
every adjustment on save without it.

**Current behavior:**
The editor's save step sets the canvas 2D context's `filter` property to a CSS filter string
(grayscale/brightness/contrast) immediately before drawing the cropped image onto the output
canvas that gets exported as the baked JPEG. On browsers that don't implement this property
(Safari, desktop and iOS, all versions), the assignment is silently ignored and the exported image
carries none of the operator's adjustments — while the live on-screen preview (a separate, plain-
CSS-on-a-DOM-element codepath) correctly reflects them, so the operator has no visual cue that the
save will differ from what they see.

**Desired behavior:**
The baked/exported image reflects the grayscale/brightness/contrast settings on every browser,
including Safari, matching what the live preview already shows. The visual result should match
today's Chrome/Firefox output (i.e., match the standard CSS Filter Effects formulas), not just be
"roughly similar."

**Key interfaces:**
- The component's filter-string builder (currently `filterString()`) stays the source of truth for
  what adjustments are active — the save path should stop feeding it into a canvas-context
  `filter` property and instead use it to drive per-pixel channel math applied via
  `ImageData`/`getImageData`/`putImageData` on the cropped source before the existing
  scale/shear/draw step runs.
- The three transforms and their order (grayscale, then brightness, then contrast — matching the
  filter string's existing left-to-right order) must be preserved exactly, using the standard CSS
  Filter Effects formulas: grayscale via Rec. 709 luma weights (0.2126/0.7152/0.0722), brightness as
  a per-channel multiply, contrast as a per-channel scale around the 128 midpoint. Clamp each
  channel to [0, 255] after each stage.
- The existing scale-to-1568px-long-edge and shear-for-skew steps are purely geometric and should
  be left alone — only the *source* of the pixels handed to that final `drawImage` call changes
  (today: the raw cropped canvas + a context filter; after: a canvas whose pixels have already been
  color-adjusted).
- The live preview (CSS `filter` on the DOM stage element) is unaffected — it already works
  correctly everywhere and needs no change.

**Acceptance criteria:**
- [ ] On a browser without `CanvasRenderingContext2D.filter` support, saving with grayscale checked
      produces a genuinely grayscale exported image (equal R/G/B per pixel, following the luma
      formula above) — not a color image.
- [ ] Brightness and contrast adjustments are also correctly baked on such a browser (both were
      silently dropped by the same root cause, not just grayscale).
- [ ] On a browser that *does* support the context filter (e.g. Chrome), the exported image is
      visually unchanged from today's output — no regression from switching to the manual path.
- [ ] Zero-adjustment saves (grayscale off, brightness/contrast at neutral 100) still export
      unmodified pixels (aside from the existing scale/shear/EXIF-upright baking), on every browser.
- [ ] The existing long-edge downscale cap and skew shear still apply correctly after the change —
      geometry is untouched by this fix.
- [ ] No new external dependency or library is introduced; the fix stays within the existing
      `receipt-editor.js` leaf.

**Out of scope:**
- Any change to the live CSS preview — it already works correctly on every browser.
- Issue 13 (the Safari datalist-styling bug) — unrelated root cause, already resolved separately.
- Adding a JS test tier for this leaf (this repo has none, per CLAUDE.md §1.6) — verify by manual
  testing in both a supporting and a non-supporting browser (or by disabling the `filter` property
  behind a feature-detect flag during manual testing) rather than an automated test.
- Any change to what gets sent to the AI parser beyond the (now-correctly-adjusted) image bytes
  themselves.

## Comments

Filed 2026-08-18 from first real-conditions testing after the Pi deployment. Root cause confirmed
same day via caniuse (Safari has never shipped `CanvasRenderingContext2D.filter`) after the owner
independently found a related but distinct Safari-specific rendering bug in issue 13. Moved
directly to `ready-for-agent` — root cause is confirmed (not a guess) and the fix path has no open
design questions, but the owner has deferred implementation for now.

Fixed 2026-08-19 (stage/9h, a455978): `save()` now bakes grayscale/brightness/contrast into the
cropped canvas's own pixels via getImageData/putImageData before the existing scale/shear draw,
instead of relying on `ctx.filter`. `/code-review` ran clean (no findings). Manual cross-browser
verification (Chrome vs. Safari, per the issue's own acceptance criteria) is still owed by the
owner — this repo has no JS test tier for this leaf (CLAUDE.md §1.6).
