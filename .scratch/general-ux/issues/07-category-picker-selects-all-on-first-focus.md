# Category pickers select their whole value on first focus, like amount fields

Status: open
Type: improvement

## Symptom
A category picker is a text `<input list=…>` with a datalist. In practice it is used as a
type-to-search dropdown, not as a text to edit. Typical case: open a processed receipt, see a line
with the wrong category, click its category field. The caret lands where the click was, so the
typed name goes into the middle of the old category name. To replace the value the operator has to
press `Home`, `Shift+End` first, or triple-click.

## Root cause
The "select all on first focus" behaviour in `keyboard.js` (`onMouseDownSelect` /
`onFocusInSelect` / `onMouseUpSelect`) only applies to `.num` inputs (`isNumField`). Category
inputs are not `.num`, so they get the browser's default caret placement.

## Accepted solution (owner's decision)
Category pickers behave **exactly like amount fields do now**:
- When focus first lands on the field (a single click or Tab), its whole value is selected, so
  typing replaces it.
- A further click in a field that already has focus places the caret as usual.
- An empty field (placeholder only) is unchanged.

This applies **everywhere a category picker appears**:

| Screen | Field | Template |
|--------|-------|----------|
| Register entry dock (simple entry) | `#entry-category` (also its OOB auto-suggest refill) | `fragments/entry-dock.html` |
| Register split panel lines | line category | `fragments/line-editor.html` via `fragments/split-panel.html` |
| Receipt processing lines | line category | `fragments/line-editor.html` via `receipt-process.html` |
| Recurring template editor lines | line category | `fragments/line-editor.html` via `split-panel.html` / `recurring-editor.html` |
| Statement match dock | category | `fragments/statement-dock.html` |

The import review "New category (Parent - Child)" fields (`import-review.html`) are out of scope:
they are for typing a new path and usually start empty.

## Implementation notes
- Stays inside the existing `keyboard.js` leaf (CLAUDE.md §1.6). Reuse the existing three
  handlers; widen what they match rather than adding a second mechanism. A markup marker on the
  input (instead of growing a list of ids/classes in JS) keeps it markup-driven.
- Browser test (`browserTest`): extend the existing select-on-focus check in
  `RegisterKeyboardBrowserTest` (amount field, ~line 144) with a category field: first click
  selects all, second click collapses the selection. One case per host (receipt, statement,
  recurring) only if the field markup differs there.

## Open question
Payee, account and tag pickers are also datalist inputs with the same caret behaviour. Not part of
this issue unless the owner says so.
