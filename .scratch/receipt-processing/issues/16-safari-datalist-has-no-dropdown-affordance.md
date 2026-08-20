# Safari shows no dropdown at all on the category / account pickers

Status: wontfix
Category: bug
Severity: high
Area: every free-text-with-create-new field — receipt post-process line editor
(`fragments/line-editor.html`), register entry dock (`fragments/entry-dock.html`), split panel
(`fragments/split-panel.html`). All share the `<input list="…">` + native `<datalist>` pattern.

Reported 2026-08-19: "the dropdown does not appear at all, no matter what I do. It does not appear
in categories and also in the account picker." Reproduces on both the Pi and localhost. Follow-up
detail from the owner: **"the little down arrow in the pickers is not displayed at all in Safari,
while it is clearly visible and working fine in Chrome."** Switching macOS to Light Appearance does
not help.

## What is ruled out

- **Not a deployment or data problem.** The Pi renders the datalists fully populated
  (`entry-account-options` carries Cash/Girocard/Advancia, `entry-category-options` the full
  category list), and serves `base.css` with the issue-13 `color-scheme: light` rule intact.
- **Not a code regression.** Since `bafe1ae` (the issue-13 fix the owner confirmed working on
  2026-08-18) the only commits touching this area are `c46cd63` (receipt-editor.js only),
  `703c4d5` (Java only) and `8571bd6` (receipt open links). No CSS and no `datalist` markup changed.
- **Not dark mode.** The first hypothesis — `color-scheme: light` pinning the popup background light
  while WebKit still drew light text — was tested by the owner and rejected.

## Probe results (2026-08-20) — the hypothesis below is only half right

Owner ran `safari-datalist-probe.html` on Safari 26.5.2 / macOS 26.5.2:

- **Safari.** The ▾ appears in **A and H only** (the two cases with no CSS on the input) and is
  drawn oversized. It is permanently visible rather than appearing on hover. **Clicking it does
  nothing, and suggestions never appear in any case — including A.**
- **Chrome.** No arrow until hover, then it appears in *every* case; clicking opens the popup and
  typing filters it. Every case works, the app's own `.input` styling included.

Case A is a bare `<input list>` with no CSS of ours anywhere near it, so **the app's stylesheet is
not the cause.** Our `background`/`border` override does cost the ▾ in Safari (B–G lost it, A/H kept
it), which confirms the native-theme half of the hypothesis below — but that is cosmetic only,
because the popup does not open even where the arrow survives. There is no CSS fix for a popup that
never opens.

Remaining confound: the probe was opened over `file://`. Re-testing over `http://` is the last step
before declaring this a Safari 26 platform bug.

## Original hypothesis (partly confirmed — explains the missing ▾, not the missing popup)

The missing ▾ is the tell: it is drawn by the browser's native form-control theme, not by our CSS.
WebKit stops painting a text input's native inner chrome once the page overrides the control's own
`background`/`border` — which `.input` in `base.css` does (`background: var(--paper)`,
`border: 1.5px solid var(--rule)`). Chrome draws its `::-webkit-calendar-picker-indicator` from the
shadow DOM instead, which survives the override — hence "fine in Chrome". If WebKit has also
dropped the list button, there is no click affordance left, and the popup can only be reached by
typing a string Safari's own matcher accepts.

## Resolution — wontfix, use Chrome for entry (owner, 2026-08-20)

The two available fixes were a cheap CSS one (restore the native ▾ on `.input`) and issue-13's
Option B (replace the native `<datalist>` with an app-styled combobox). The CSS fix is worse than
nothing — it puts back an arrow that does not respond to a click. Option B works everywhere and we
would control the look, but it is a **third bespoke-JS leaf** touching every free-text picker in
the app (entry dock, split panel, receipt line editor), and CLAUDE.md §1.6 keeps bespoke JS to the
two existing leaves.

Owner's call: **do neither.** Every picker works correctly in Chrome today, so entry happens in
Chrome and this stays parked on Apple. Revisit if a Safari update does not restore the popup, or if
Safari becomes the entry browser again.

The probe page is kept at `.scratch/receipt-processing/safari-datalist-probe.html` for that
re-test: it isolates bare input · background only · border only · the app's real `.input` ·
`.input` + `appearance: auto` · `.input` + forced `::-webkit-list-button` · `color-scheme: light`
scope · `autocomplete="off"` · pre-filled value.

Note that issue 13 conflated two complaints: the owner's *original* report was "the Categories
dropdown is not a dropdown, but just a text field… it does not make suggestions", and only the
second one (the popup's unreadable contrast) was actually fixed. This issue is the first one,
never resolved.
