# Enter should trigger "Prepare for analysis" and, in the image editor, "Save"

Status: resolved
Category: enhancement
Severity: minor
Area: Receipts — processing screen (`receipt-process.html`), keyboard leaf (`keyboard.js`)

## Symptom
Working through scanned receipts is keyboard-driven (↑/↓ walk the list, Esc/Backspace is Back,
Esc is Cancel in the editor), but the two actions the operator takes on *every* receipt still need
the mouse:

1. A `new` receipt offers exactly one action, **Prepare for analysis** (`data-receipt-edit-start`).
   Enter does nothing, because nothing on the page has focus.
2. In the image editor the operator clicks the image, drags the crop frame and uses sliders, then
   wants to confirm with Enter. Focus is wherever they last clicked, so a "focus the Save button"
   approach cannot work. **Save** (`data-receipt-save`) needs the mouse.

## Feasibility
Yes, both are doable with no new leaf and no new pattern.

- **Prepare for analysis:** give the button initial focus, so Enter (and Space) activate it natively.
  Use the HTML `autofocus` attribute on the button, rendered only in the `new` branch of the
  template — this works even without JS. It does not conflict with the ↑/↓/Backspace/Delete
  shortcuts: they are gated on `!isTyping(target)`, and a focused button is not an input.
- **Save in the editor:** a global Enter shortcut in `keyboard.js`'s `onKeydown`, placed next to the
  existing Esc → Cancel mapping and gated the same way (`receiptProcess() && receiptEditActive()`),
  which calls `clickIfPresent("[data-receipt-save]")`. It is independent of focus, which is the
  requirement. `keyboard.js` already owns "map keys onto server-rendered buttons" for this screen
  and says new shortcuts go there; `receipt-editor.js` keeps owning Cropper and the actual bake.

## Accepted solution (proposed — confirm the open points below before coding)
1. `receipt-process.html`: add `autofocus` to the **Prepare for analysis** button in the
   `receipt.state() == 'new'` block only (the `pre_processed` / other states keep their current
   focus behaviour; the issue is only about the single-action `new` screen).
2. `keyboard.js`: in edit mode, Enter clicks `[data-receipt-save]`, except when Enter already has a
   meaning for the focused element:
   - **`textarea`** (the AI note): Enter inserts a newline — no Save. (Open point A.)
   - **`button`, `a`, `summary`** (e.g. Cancel, rotate, Reset focused via Tab): Enter activates that
     control natively — no Save. Otherwise Tab → Cancel → Enter would save instead of cancelling.
   - Sliders, the grayscale checkbox, and the image/body itself: Enter → Save.
   - Ignore auto-repeat (`event.repeat`) and modified keys (Ctrl/Alt/Meta/Shift+Enter) so a held key
     cannot trigger a second bake.
3. Update the header comment of `keyboard.js` item 9 (it lists Esc = Cancel; add Enter = Save in
   edit mode, and the initial focus on `new`).
4. After **Cancel** from the editor on a `new` receipt the read view comes back with nothing
   focused; re-focus **Prepare for analysis** so Enter works again. (Open point B — small, but
   without it the shortcut works once and then silently stops.)

## Open points for the owner
- **A.** In the AI-note textarea, should Ctrl/Cmd+Enter save? (Proposed: no, keep it minimal;
  say if you want it.)
- **B.** Is re-focusing the button after Cancel wanted? (Proposed: yes.)

## Tests
- `browserTest` (`ReceiptKeyboardBrowserTest` / `ReceiptEditorBrowserTest` — client-side key
  behaviour is exactly what that tier is for):
  - open a `new` receipt → Enter starts the editor without any click;
  - in the editor, click the image / move a slider, press Enter → the edited copy is saved
    (state becomes `pre_processed`);
  - Enter inside the AI-note textarea inserts a newline and does **not** save;
  - Tab to **Cancel**, Enter → cancels, does not save;
  - held Enter (repeat) saves once.
- `integrationTest` (`ReceiptProcessingScreenIntegrationTest`): the `new` screen renders
  `autofocus` on the prepare button; other states do not.
- No unit tests for the template (CLAUDE.md §6).
