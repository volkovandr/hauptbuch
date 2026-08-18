# Category/account/payee/tag datalist suggestions are unreadable in Safari

Status: resolved
Category: bug
Severity: medium
Area: Receipts — post-process line editor (`fragments/line-editor.html`); also the register entry
dock (`fragments/entry-dock.html`) and split panel (`fragments/split-panel.html`), which share the
same input+`<datalist>` pattern for category/account/payee/tag fields.

Found during first real-conditions use after the Pi deployment (2026-08-18): "when editing a
processed receipt the Categories dropdown is not a dropdown, but just a text field... It does not
make suggestions regarding categories, but when I typed the name of the category (without the full
parent hierarchy) it worked. It also worked for a transfer: I entered 'for Bobby' ... it still
worked correctly."

## Triage session (2026-08-18)

Initial read: not a regression — every free-text-with-create-new field in the app (category,
account, payee, tag) deliberately uses the same `<input list="...">` + native `<datalist>` +
`/categories/resolve`-style htmx round-trip, across `line-editor.html`, `entry-dock.html`, and
`split-panel.html`. No richer combobox exists anywhere in the app to point to as "already built."
The one field using a real `<select>` is currency (`currency-picker.html`), which is a small bounded
enum, not a counter-example. Initially filed as `enhancement`/`needs-info`, asking whether the
owner wanted a real dropdown built at all.

**Owner re-tested across browsers and reclassified this as a real bug:** it works fine in Chrome,
but in Safari "the dropdown appears in light-grey on dark-grey for a second, but after a sec it
gets light-grey on white which makes it practically invisible on the page background and the text
is almost invisible anyway... the feature actually works, but differently in Safari, in the way
making it unusable."

**Root cause (verified via the reporter's own cross-browser repro, no further reproduction
needed):** the app has no dark theme at all — `base.css` is a single light "ledger paper" palette,
no `prefers-color-scheme`/`color-scheme` anywhere prior to this session. Unlike `<select>`, Safari
(WebKit) exposes essentially no CSS hooks for a `<datalist>` popup's background/text — it's native
OS chrome our stylesheet can't reach. With no `color-scheme` declared, Safari has to guess which
native palette to draw the popup in, and the reported flicker (transient dark-grey-on-dark-grey,
settling on light-grey-on-white) matches Safari re-resolving that guess mid-render — independent of
anything the app's own CSS requests.

## Fix under test (Option A — cheap, no architecture change)

Added `color-scheme: light;` to `:root` in `base.css` (2026-08-18) — pins Safari to always draw
native widgets (datalist popups, scrollbars, date inputs, etc.) using its light palette instead of
guessing/flickering. Zero JS, one CSS declaration, fully reversible.

**Owner confirmed (2026-08-18): fixed.** Re-tested in Safari after pulling the change — the
datalist suggestion popup now renders legibly.

## Resolution

`color-scheme: light;` on `:root` in `base.css` was sufficient — no need for Option B (replacing
the native `<datalist>` with an app-styled combobox), so the §1.6 bespoke-JS-leaf question never
came up. Applies app-wide, so it also covers the same datalist pattern on the register entry dock
and split panel, not just the receipt line editor.
