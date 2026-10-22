# Keyboard shortcuts for reviewing a processed receipt: A, D, C and Ctrl+Enter

Status: open
Category: enhancement
Severity: minor
Area: Receipts — processing screen, read mode (`receipt-process.html`), keyboard leaf (`keyboard.js`)

## Symptom
Reviewing a processed receipt is repetitive. Most receipts are fine and only need Confirm; the
usual defects are a missing paying account, a missing category on one item, and (rarely) a date the
analysis did not recognise. Each of these needs the mouse today.

## Accepted solution (owner's decisions)
All shortcuts live in the existing keyboard leaf (`keyboard.js`, next to the processing-screen
handler gated by `receiptProcess()`); no new leaf, no new pattern. They apply on the processing
screen in **read mode** only — edit mode keeps its own Enter = Save / Esc = Cancel.

| Key | Action |
|-----|--------|
| **A** | Focus the paying-account input (`#receipt-account`) with its whole content selected, as if clicked, so typing replaces the old value. |
| **D** | Focus the date field (`#receipt-date`). |
| **C** | Focus the category input of the first item that has no category. Pressing C again, from a non-typing focus, goes to the next such item (wrapping). When every item has a category, C does nothing. |
| **Ctrl+Enter** | Confirm the receipt from anywhere, text fields and textareas included. |

- **A, D and C are disabled while focus is in a text field or textarea** (Note, Item description,
  Tags, Category, Account, Amount, Date…) — there they type a normal letter. They work from the
  page body or from a focused button (any button), reusing the existing `isTyping()` check.
- **Ctrl+Enter** clicks the existing Confirm button (`data-receipt-confirm`), so it inherits every
  refusal the button already has (e.g. an unresolved account such as "foo" — "account does not
  exist" — still blocks the confirm). It calls `preventDefault()`, so a textarea gets no newline.
  It is **Ctrl on every platform, including macOS; Cmd+Enter does nothing** (check `ctrlKey` only,
  ignore `metaKey`).
- A focused button keeps its own Enter, as today.

### Decisions that shape this (why it is smaller than first discussed)
- **No plain-Enter confirm.** An earlier idea — Enter confirms when a resolved single-line picker
  has its text selected and the dropdown is closed — was dropped. The tag field's chip-commit /
  collapsed-dropdown states made it too intricate; Ctrl+Enter replaces it and works in every state.
  Picker, Tags and Note keep their current Enter behaviour untouched (the open tag issue stays
  separate).
- **No modifier variants for A/D/C** (Ctrl+A would break Select All; Alt+A/Ctrl+Shift+A were
  rejected). Plain letters, typing fields excluded.
- **D on the native date input.** `#receipt-date` is `<input type="date">`; clicking it selects the
  day segment, and D must do the same — plain `focus()`, no whole-field selection (Tab then moves
  to month, as today; see `general-ux/issues/04`).
- **C iteration is accepted as clumsy.** After C jumps into a category field it is a typing field,
  so C/A/D type letters there. Repeating C needs focus to leave the field first (after Amount, Note
  and Tags, i.e. Tab ×4 to reach a button). The owner accepts this: usually only one category is
  missing, and with several the mouse is fine. No Alt+C.
- **Unresolved picker text is untouched.** Typed text that matches no account/category: Enter in
  the picker clears it, and clicking away leaves it with the "does not exist" message; Confirm
  (button or Ctrl+Enter) refuses in that state. No new validation is added.

## Notes
- "No category" = a line's category input (`name="categoryText"`, `fragments/line-editor.html`)
  with no resolved id; settle the exact selector against the rendered markup when implementing.
- `data-receipt-confirm` already exists on the Confirm button; it needs no new hook.
- Uncommitted text in the Tags input is not a chip yet and is dropped by Confirm, Ctrl+Enter
  included — same as clicking the button today.

## Tests (`browserTest`, Playwright — client-side behaviour only)
- A focuses the account input with its content selected; typing replaces it. D focuses the date.
- A/D/C type a normal letter inside Note, Item description, Tags, Category, Account.
- A/D/C work from the body and from a focused button.
- C lands on the first uncategorised item, does nothing when all are categorised, and moves to the
  next one when pressed again from a non-typing focus.
- Ctrl+Enter confirms from inside the Note (no newline inserted) and from a picker; Cmd+Enter does
  not; an unresolved account still blocks it.
- Enter on a focused button still presses that button.

Update the keyboard section of `docs/ui-receipt-processing.md` §6 and the header comment of
`keyboard.js` (item 9) when implementing.
