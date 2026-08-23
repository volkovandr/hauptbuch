# `LONG_EDGE_CAP` is the real dial on receipt image cost and quality — and its comment is stale

Status: needs-triage
Category: enhancement
Severity: low
Area: Receipts — image pre-processing bake (`receipt-editor.js`)

Found 2026-08-23 while assessing `receipt-processing/21` (erasing regions), which was dismissed. The
one durable finding from that investigation is this.

## The finding

`receipt-editor.js` bakes the edited scan down to a long edge of 1568 px:

```js
var LONG_EDGE_CAP = 1568; // The Anthropic API downscales beyond this anyway (receipt doc §6.1).
```

That comment **is no longer true.** Claude's vision tiers:

| Tier | Models | Max long edge | Max visual tokens |
|---|---|---|---|
| High-resolution | Claude 4.7 and later | 2576 px | 4784 |
| Standard | everything older | 1568 px | 1568 |

The app defaults to `claude-sonnet-5` (`AiSettings`, `settings.ai_model`), which is
high-resolution tier. So the API would accept 2576 px, and the app is voluntarily downscaling well
below that — the cap is no longer matching the API's behaviour, it is now an independent
cost/quality choice that nobody has deliberately made.

## Why it matters

Image input is billed by area: `⌈width / 28⌉ × ⌈height / 28⌉` visual tokens, independent of what is
depicted. So this single constant is the *only* real lever on what a receipt parse costs and how
legible the scan is to the model — nothing about editing the image's content can move it (which is
exactly what killed issue 21).

A narrow 1000×4000 receipt today: capped to 392×1568 → 14 × 56 = **784 visual tokens**, roughly
$0.002 per parse. Raising the cap toward 2576 would give the model a sharper scan at a
proportionally higher (still tiny) cost; lowering it does the reverse.

## What to decide

- Is parse quality on long receipts good enough today? (Owner's answer as of 2026-08-23: yes, no
  quality problems observed.) If so, the correct action may be **only to fix the stale comment**,
  recording that 1568 is now a deliberate cost choice rather than an API constraint.
- If quality ever does become a problem — a long receipt whose items parse poorly — raising this
  constant is the first thing to try, before anything involving new UI.
- Worth noting the cap interacts with the model setting: pointing `settings.ai_model` at a
  standard-tier model would make 1568 the API's real limit again, so any comment should describe
  the tier rather than assert a fixed number.

## Comments

Filed 2026-08-23 as the surviving half of the `receipt-processing/21` investigation. Deliberately
small: one constant and one comment, no new UI, no new JS.
