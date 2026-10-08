# d1 dock: category never resolves, dock placement, sign display, errors, tags

Status: open

## Symptom
Owner testing of d1 ("Create missing") found:
1. The dock opens under the lines table; with a long table the operator must scroll to it.
2. The amount shows `-20,00`; in the register a leading `−` flips the direction, so it reads as a storno
   (the server actually books the bank's sign: a −20 line is a plain expense).
3. Saving after picking a category fails with "A category, transfer target, or person is required".
   The error is shown at the top of the page after a redirect, and the dock comes back empty.
4. Creating a new category from the dock never offers the "Create" confirmation.
5. The Category field sits higher than its neighbours because the bank category is rendered under it.
6. No tags; the Account field is redundant (inherited from the statement).

## Root cause
- 3/4: the dock `<form>` sets `hx-target`/`hx-select`/`hx-swap` for the whole-page swap. All three are
  inherited by the Category input's `/categories/resolve` call, which overrides only target and swap.
  `hx-select="main"` filters the returned fragment to nothing, so the hidden `categoryId` (and the
  Create confirmation) are wiped. Integration tests post `categoryId` directly, so they never saw it.
- 3 (placement): the refusal is a flash message + redirect, so the message lands at the page top and the
  entered values are lost.

## Accepted solution (owner's decisions)
- The Category input resets `hx-select` (`unset`); a `browserTest` drives pick → save and create-category.
- The amount is shown as a magnitude, **no caption** (the dock sits under its line).
- A refused save re-renders the dock in place, keeping what was typed, with the message beside Save.
- The dock is a full-width row directly under the clicked line; its `<form>` lives outside the lines
  form and the row's inputs reach it through the HTML `form` attribute.
- Bank category moves to the dock header under "Create transaction".
- The Account field goes; tags (register chips → `DockEntry.tagIds`) are added.
- Split transactions are out of scope here: see issue 06.
- Foreign-line pre-fill stays deferred to the PDF stage (e), when real examples exist.
