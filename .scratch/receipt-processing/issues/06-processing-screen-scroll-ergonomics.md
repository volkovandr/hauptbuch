# On a long receipt, the image and the Save actions scroll out of view with the line list

Status: resolved
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

## Owner decisions (triage, 2026-08-10)

1. **Image column** — its own independent scroll region, not a plain `position: sticky` pin. Within
   that region the image auto-zooms to the full width of the region, capped at its own natural
   (original) size — never upscaled past that.
2. **Sticky bottom bar** — narrow scope: only the `remaining` readout + Save/Confirm. `＋ Add line`
   stays in normal document flow above it and scrolls away with the line list.

## Agent Brief

**Category:** enhancement
**Summary:** On the receipt processing/review screen, make the receipt image independently
scrollable (not tied to the line-list scroll) and pin the remaining-amount readout + Save/Confirm
to the bottom of the viewport, so both stay usable on a receipt with enough lines to scroll.

**Current behavior:**
The processing screen lays the receipt image and the line editor out as a two-column grid with no
scroll boundary of its own — the whole page is one scrolling document. On a receipt with enough
lines, scrolling the line list also scrolls the image out of view (defeating line-by-line
comparison against the printed receipt) and scrolls the remaining-amount readout and Save/Confirm
buttons out of view (forcing a scroll to the bottom every time to save).

**Desired behavior:**
- The image column becomes its own independently scrollable region, separate from the line-editor
  column's scrolling. Within that region, the image is auto-sized to fill the region's full width,
  but never upscaled beyond its natural/original pixel size — a small original image should not be
  stretched to fill a wide region. A receipt image taller than the region remains reachable by
  scrolling within the region itself, not the page.
- The remaining-amount readout and the Save/Confirm actions are pinned to the bottom of the
  viewport (or their column, whichever the existing layout naturally supports) so they stay visible
  regardless of how far the line list has scrolled. The `＋ Add line` control is explicitly **not**
  part of this pinned area — it stays in normal flow with the line list.
- The pinned bar needs an opaque background (correct in both light and dark themes) so scrolling
  line rows don't visually show through underneath it.
- On a receipt short enough that nothing scrolls, behavior is visually unchanged — no stray
  scrollbar or pinned-bar seam appears where there's nothing to pin against.
- The read-only (committed) rendering of this same screen shows the remaining readout without the
  Save/Confirm actions (they're absent entirely when the receipt is already committed) — the pinned
  area must still look correct with just the readout and no action buttons underneath it.

**Key interfaces (durable names — locate them, don't trust line numbers):**
- The processing screen's read/review layout (the two-column image + editor grid) — CSS-only change,
  no template restructuring should be needed for the scroll region.
- The line-editor's live remaining-amount readout and its Save/Confirm actions, which sit at the
  tail end of the same form the line list belongs to — these are two adjacent pieces of markup, one
  inside the disabled-on-readonly fieldset and one just outside it; both need to end up in the same
  visually pinned area regardless of that markup boundary.
- The register screen already uses `position: sticky` for an analogous sticky-header problem — follow
  that established technique/precedent rather than introducing a new mechanism (no JS; CLAUDE.md
  §1.6 keeps bespoke JS out of this).

**Acceptance criteria:**
- [ ] On a receipt with enough lines to make the editor column scroll, the receipt image is
      reachable via its own scroll, independent of the line-list scroll position.
- [ ] The displayed image fills the full width of its scroll region but is never scaled larger than
      its natural size.
- [ ] The remaining-amount readout and Save/Confirm buttons stay visible at the bottom of the
      viewport while scrolling through a long line list; `＋ Add line` is not part of that pinned
      area and scrolls with the list.
- [ ] The pinned bar has an opaque background in both light and dark themes — scrolling line rows do
      not show through it.
- [ ] A short receipt (no scrolling needed) renders with no visible change from today.
- [ ] The read-only/committed rendering (no Save/Confirm) still renders correctly with just the
      remaining readout in the pinned area.
- [ ] `./gradlew check` green. Since this is CSS/layout-only, no new automated test is expected to be
      added for the visual behavior itself — verify by hand in the browser per CLAUDE.md's UI-change
      guidance.

**Out of scope:**
- Any change to the line-editor's markup, its htmx wiring, or `keyboard.js`'s live-recompute logic —
  this is a pure layout/CSS change.
- The field-width bullet from the same owner note (`docs/potential-feature-ideas.md`) — the owner is
  handling that one separately.
- Responsive/narrow-viewport collapsing of the two-column grid — not currently handled and not part
  of this ask.

## Comments

Filed 2026-08-09 from two bullets in the owner's `docs/potential-feature-ideas.md` note (the third,
field-width, bullet in the same note is out of scope here — the owner is exploring a CSS-only fix
for that one directly). Left at `needs-triage`: worth a quick decision on the two open points above
before this becomes an agent brief, but both are small enough that a short owner sign-off should be
enough — no full grilling session expected.

> *This was generated by AI during triage.*

Triaged 2026-08-10: owner picked an independent scroll region (not sticky-pin) for the image, with
auto-zoom to the region's width capped at natural size; and the narrow scope for the sticky bottom
bar (remaining + Save/Confirm only, `＋ Add line` excluded). Moved to `ready-for-agent` with the
brief above.
