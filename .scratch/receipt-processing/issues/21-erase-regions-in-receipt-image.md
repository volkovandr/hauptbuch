# No way to erase parts of a receipt image before analysis (marketing blocks, QR codes)

Status: wontfix
Category: enhancement
Severity: medium
Area: Receipts — image pre-processing editor (`receipt-editor.js`, Cropper.js leaf)

> *Filed rough on 2026-08-23, examined the same day, and dismissed — see **Why this was
> dismissed** below. Both of its stated justifications turned out not to survive contact with how
> image tokens are actually counted and what the parse request actually sends.*

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

## Why this was dismissed (2026-08-23)

Both stated motivations were checked against the Anthropic vision documentation and against this
repo's own parse call. Neither survived.

### 1. Erasing does not save tokens — and cutting a band out costs *more*

Image input is billed by **area, not content**: Claude sees 28×28-pixel patches, so an image costs
`⌈width / 28⌉ × ⌈height / 28⌉` visual tokens. What is *depicted* is irrelevant.

- **Painting a region white saves exactly zero.** Same dimensions, same patch count. The marketing
  block still costs what it cost.
- **Excising a band from a long receipt makes it more expensive.** Images above the model's
  long-edge limit are downscaled first, so removing height only makes the aspect ratio less extreme
  and the downscale gentler — more pixels survive, in the dimension that was not cut. Worked
  through with the app's own `LONG_EDGE_CAP` of 1568:

  | | Dimensions | After the 1568 cap | Visual tokens |
  |---|---|---|---|
  | Original scan | 1000 × 4000 | 392 × 1568 | 14 × 56 = **784** |
  | Minus a 1000 px band | 1000 × 3000 | 523 × 1568 | 19 × 56 = **1064** |

  ~35% *more*, for having removed content. Cutting only reduces tokens when the image is already
  shorter than the cap, which a phone photo of a long receipt never is.

The genuine effect of excising is **better legibility** — the surviving content is rendered at
higher effective resolution inside the same budget. That is a real benefit, but the owner reports no
parse-quality problems, so it is a solution without a problem.

### 2. The QR-code security argument does not hold either

- **Claude very likely cannot decode the QR at all.** A QR is a dense, error-corrected binary
  matrix; recovering its payload is a specific decoding algorithm, not a perception task. The model
  recognises *that* a QR is present and typically cannot read what it encodes. The premise "the AI
  reads everything, including the QR" is false for precisely the element the ticket worried about.
- **The parse request carries no tools.** `AnthropicReceiptParser.parse` builds a plain
  `messages.create` — system blocks, one base64 image, one text block. No web search, no web fetch,
  no code execution, no MCP. A malicious URL that the model merely *read* would be inert: nothing in
  the request can visit it.
- **There is nothing to exfiltrate.** ARCH-08 means the request carries the document, the parse
  instructions and the AI Vocabulary — no transactions, no balances. An injection asking for ledger
  contents would be answered by a model that has never seen them.
- **A human reviews before anything is booked.** The parse produces TOON text that seeds a draft;
  the operator reviews it in the post-process editor and Confirm hard-blocks. The worst realistic
  outcome of any successful injection is a wrong category or a bogus line, visible on screen before
  it becomes a posting.

The residual risk, stated fairly: adversarial **printed text** (not the QR) could try to steer the
parse. Real in principle — but it would require a merchant printing an attack on a receipt, aimed at
a self-hosted single-user ledger they do not know exists, to achieve a mis-categorised line the
operator reviews anyway.

### 3. The cost at stake is under a quarter of a cent

At 784 visual tokens and `claude-sonnet-5` input pricing, a receipt image costs roughly **$0.002**.
Building the feature means ~150 lines of new pointer-handling canvas JS — the largest bespoke-JS
addition the project would have made, in a layer with **no test tier at all** (the browser tier was
dropped, so nothing would cover the half most likely to break). The economics do not survive
stating.

### The one thing worth keeping from this investigation

**The real token dial is the long-edge cap, not the image content.** `LONG_EDGE_CAP = 1568` in
`receipt-editor.js` decides the bill, and its comment — *"The Anthropic API downscales beyond this
anyway"* — is now **stale**: the app calls `claude-sonnet-5`, which is high-resolution tier and
accepts **2576 px / 4784 visual tokens**. The app is therefore voluntarily downscaling well below
what the model would take. Lower that constant and every receipt gets cheaper immediately; raise it
and every receipt gets sharper. One number, no new UI, no new JS. Filed separately as
`receipt-processing/24`.

### What would revive this ticket

- Parse quality on long receipts turning out to be a real problem that raising `LONG_EDGE_CAP`
  does not fix.
- Vision models becoming reliable QR decoders **and** the parse call gaining tools or network
  access — both would have to happen.
- A use case that is about *what the operator wants the model to ignore* rather than about cost or
  security. That would be a different ticket with a different justification.

## Comments

Filed 2026-08-23 from the owner's real-use notes. Related to issue 12 (the Safari filter-bake fix)
only in that both live in the same client-side bake path.

---

Dismissed 2026-08-23 after the owner questioned both premises in turn — first whether cutting a
band from a tall image saves anything (it does not; it costs more), then whether the QR-code
security concern was serious at all (it is not, for four independent reasons). Out-of-scope entry:
`.scratch/receipt-processing/.out-of-scope/erase-regions-in-receipt-image.md`.
