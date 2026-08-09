# On a long receipt, the image and the Save actions scroll out of view with the line list

Status: needs-triage
Category: enhancement
Severity: low
Area: Receipts — processing screen layout (`receipt-process.html`, `receipts.css`), post-process
editor (plan §9f)

Two related owner asks from `docs/potential-feature-ideas.md`, both about the same pain: reviewing
a receipt with enough lines that the form scrolls, on the processed/committed pane.

1. **The receipt image should stay in view while the line list scrolls.** Today
   `.receipt-process__read` (`receipts.css:398-403`) is a two-column CSS grid — the image `<figure>`
   (`receipt-process__image`) on the left, the editor (`receipt-process__side`) on the right,
   `align-items: start`. Neither column has its own scroll region or `position: sticky`; the whole
   page scrolls as one document, so once the line list is long enough to scroll, the image scrolls
   away with it — exactly when the owner wants it (checking a line against the printed receipt).
2. **The remaining-amount readout + Save/Confirm should stay reachable without scrolling.** The
   live `remaining` status (`.entry-dock__status.split-status`, `data-split-remaining`,
   `receipt-process.html:659-674`) and the `<p class="actions">` Save/Confirm buttons
   (`receipt-process.html:681-694`) sit in normal flow at the very end of the line-editor `<form>` —
   after however many `<li class="split-line">` rows the receipt has. On a big receipt, saving
   means scrolling all the way down every time, even though `keyboard.js`'s `data-split-*` readout
   already recomputes `remaining` live client-side as you edit (no round trip needed to see it
   update) — so the number is ready long before it's back in view.

## Why this is plausibly a small fix

Both are pure layout/CSS, not JS or server logic — no line-editor markup or behavior needs to
change, and CLAUDE.md's JS-leaf restriction (§1.6) doesn't come into play at all here. The codebase
already uses `position: sticky` for an analogous problem: the register's own table header
(`register.css:62-75`, `position: sticky; top: 0` within a `max-height` + `overflow: auto`
container) stays in view while the table body scrolls underneath it. The two asks here are the same
technique pointed the other way — a sticky/independently-scrolling image column, and a sticky
bottom bar for the status + actions — rather than a novel mechanism.

Two things worth deciding before implementing (not resolved here):

- Whether the image column gets its **own independent scroll region** (so a receipt taller than the
  viewport can still be scrolled to see its bottom) or is simply pinned via `position: sticky` at
  its current height (simpler, but a very tall receipt image would be clipped by `max-height: 78vh`
  either way per the existing rule at `receipts.css:405-411`).
- Whether the sticky bottom bar should cover just `.entry-dock__status` + the actions `<p>`, or the
  whole `<fieldset>` end area — and how it looks against the page background on both themes (a
  sticky bar needs an opaque background so line rows don't show through as they scroll under it).

## Comments

Filed 2026-08-09 from two bullets in the owner's `docs/potential-feature-ideas.md` note (the third,
field-width, bullet in the same note is out of scope here — the owner is exploring a CSS-only fix
for that one directly). Left at `needs-triage`: worth a quick decision on the two open points above
before this becomes an agent brief, but both are small enough that a short owner sign-off should be
enough — no full grilling session expected.
