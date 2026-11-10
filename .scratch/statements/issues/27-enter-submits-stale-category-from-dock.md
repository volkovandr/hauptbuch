# Statements: Enter in the Create dock submits the previously resolved category, not the one just picked

Status: ready-for-agent
Category: bug
Area: statement dock (`fragments/statement-dock.html`), the category picker's `change` →
`/categories/resolve` round trip, possibly `keyboard.js`
Design: `docs/statements.md`; `docs/ui-transaction-register.md` (entry dock; Tab walks the fields,
Enter submits)

## Symptom
On the statements page, **Create** on an unmatched line opens the transaction dock.

1. Focus Category, type, pick a suggestion with the cursor keys, press **Enter** to submit.
   The transaction is booked with the category that was in the picker *before*, not the one picked.
   Only leaving the field first (Tab, or a mouse click elsewhere) makes the pick stick.
2. Worse for transfers/people: type the person's name, select "for <person>" with the cursor keys.
   A small hint appears on the right confirming the selection (the resolved-category fragment), yet
   Enter without moving focus first still books the previous category.

## Root cause (analysed from code; not reproduced in a browser)
The form posts the hidden `categoryId` (`#entry-category-resolved`), never `categoryText`; the text
input only feeds `/categories/resolve`, which fills that hidden id **asynchronously** on `change`
(`statement-dock.html:95-98`). Enter in a text input fires `change` and the implicit form submit in
the same keystroke, so htmx serialises the form before the resolve response has swapped in the new
id: the **old** `categoryId` is booked. The hint on the right is that response landing after the
submit left. Nothing server-side catches it: the statements Create handler uses `categoryId` and
treats `categoryText` as redisplay only (`DockInput`, `StatementDockService`), and the register's
`RegisterEntryController` only refuses a *null* id, so a stale non-null id passes.

It is **shared, not statements-specific**: the register dock (`entry-dock.html:260`), the line
editor (`line-editor.html:69`) and the split panel (`split-panel.html`) use the identical
`hx-post="/categories/resolve" hx-trigger="change"` picker, and the receipt screen already works
around the same race for Ctrl+Enter (`confirmReceipt()` in `keyboard.js`: blur, then wait until
htmx is idle). Tab/mouse work because the resolve has time to finish while the operator moves on.

## Accepted direction (agent brief)
Fix once, client-side, in the `keyboard.js` leaf (CLAUDE.md §1.6; no new leaf, no new JS file):
on Enter in a `data-select-on-focus` picker input inside a form, do not let the native submit run
immediately. Instead `preventDefault()`, fire the field's `change` (blur it, as `confirmReceipt()`
does), wait until no `.htmx-request/.htmx-swapping/.htmx-settling` element remains (same
quiet-check loop, bounded; factor it out of `confirmReceipt()` rather than copy it), then
`form.requestSubmit()`. Guard against a second Enter while one is pending. Enter on any other field
is unchanged, and the tag-chip input keeps its own Enter handling.

Not chosen: server-side re-resolution of `categoryText` on submit. It would duplicate the resolver
(create-new categories, `for <person>`, pending-confirm states) in every submit handler.

## Tests
- `browserTest` (Playwright, new class seeding its own names): in the statement Create dock *and*
  the register dock, type a category, pick with ArrowDown, press Enter immediately — the booked
  category is the picked one; same for a `for <person>` target. Seed a different pre-filled category
  so a stale id is distinguishable. Server-side flows stay in `integrationTest`.
- No unit test: pure client behaviour.

## Comments
