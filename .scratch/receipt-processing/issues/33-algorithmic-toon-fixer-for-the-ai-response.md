# Offer a deterministic "Fix TOON" button next to the edit-and-re-parse textarea

Status: ready-for-agent
Category: enhancement
Severity: medium
Area: Receipts — processing screen (`receipt-process.html`, `ReceiptProcessingController`) × a new
pure repair class in `receipts`

Raised 2026-09-30 by the owner. When the model's TOON body is malformed, the operator fixes it by
hand in the "Details" textarea and presses "Parse my edited text" (`ReceiptAnalyser.reparse`). For
large receipts the hand edit is tedious. The owner tried asking Haiku to repair the text
interactively and the result was unusable, so an **AI repair is rejected**. The failures seen so far
are mechanical and can be fixed by a plain algorithm — free, instant, deterministic.

## Observed error types

1. An item name containing a comma, unquoted: `Item One 3,5,,,1.69,…` (the row has one comma too
   many).
2. An item name containing a double quote that is wrongly quoted or unescaped.
3. `items[N]` not matching the number of rows in the table.

Also seen, rarely, and **not fixable** beyond cosmetics: markdown artifacts around the body, and a
response cut off mid-way (`max_tokens`).

## Desired behavior

A second button beside the existing submit, on both textareas that hold `parse_raw` (the `failed`
"Details" block and the `processed` re-seed block): **"Fix TOON automatically"**.

- It posts the textarea's **current contents** (so unsaved manual edits are kept), runs the repair,
  and puts the **repaired text back into the textarea**, plus a short list of what changed
  (e.g. "row 1: quoted the name; `items[31]` → `items[32]`") or "Nothing to fix".
- **It must not write anything to the backend.** No receipt row, state, `parse_raw`, token count or
  cost changes. Only the existing submit ("Parse my edited text" / "Re-seed from this text")
  saves, exactly as today. The operator reviews the repaired text first.
- Because nothing is saved, the endpoint is a read-only, htmx-style fragment swap (the textarea and
  the change list), not a redirect. It needs no receipt state check beyond the receipt existing.

## The repair rules (a pure function: text in → repaired text + change list out)

Applied to the `items[N]{cols}:` table; `columns` = the header's field count (8 today), so a
well-formed row has `columns − 1` commas.

1. **Commas in names.** Per row, take the last `columns − 1` commas from the right; everything
   before them is the name. If that name is not already a well-formed quoted string, double-quote
   it. Columns after the name never contain commas (tags are comma-separated *inside one cell*
   only when the prompt says so — see the Open point below), so counting from the right is safe.
2. **Quotes in names.** Same right-to-left split, then normalise the name: strip a stray outer
   quote pair, escape inner quotes as `\"` (TOON's escape), and wrap in quotes. A correctly quoted
   name (e.g. `"Tomate,Flei. bunt"`) is left untouched.
3. **Item count.** Count the real rows and rewrite `items[N]` to that number.
4. **Cosmetics.** Strip a surrounding code fence and any prose before the first `merchant:`
   (`ToonReceiptDecoder.unfence` already tolerates fences on decode, but the operator sees them in
   the textarea).

**Flag, don't fix** (listed in the change list as a warning, text left as is):

- A row with **fewer** commas than expected (missing fields — no safe guess).
- A last row that is incomplete (cut-off output). Never silently drop it.

The algorithm is a heuristic; the change list plus operator review before saving is the safety net.

## Open point for the implementer

The tags cell may hold several comma-separated tags (`ReceiptPromptBuilder` line ~70: "Put multiple
tags in one cell, comma-separated"). A multi-tag cell must be double-quoted by the model; check how
the prompt/decoder treat that, and make sure right-to-left counting does not mis-split a quoted
multi-tag cell. Count commas outside quoted cells from the right, not raw commas, if needed.

## Acceptance criteria

- [ ] A pure, unit-testable repair class in `receipts` (no Spring beans needed beyond wiring) that
      returns the repaired text and a list of change descriptions.
- [ ] Rules 1–4 implemented; the two "flag" cases produce warnings and leave the text unchanged.
- [ ] The example shape above (one row with an unquoted comma in the name, rest well-formed, count
      correct) repairs to a body `ToonReceiptDecoder` decodes; an already-correct body comes back
      unchanged with "Nothing to fix".
- [ ] A new endpoint (e.g. `POST /receipts/{id}/fix-toon`) returns the repaired text for the
      textarea and the change list, and **persists nothing** — a controller acceptance test
      asserts the receipt row is unchanged after the call.
- [ ] The button appears next to the submit on both textareas (`failed` Details block and the
      `processed` re-seed block) and works without bespoke JS (htmx swap, per CLAUDE.md §1.6).
- [ ] No call to any AI provider, no new setting.
- [ ] `./gradlew check` green.

## Tests (per CLAUDE.md §6)

- **Unit (the bulk):** the repair function — unquoted comma in a name; wrongly quoted / unescaped
  quote in a name; already-quoted name untouched; wrong `items[N]` (too high and too low); fenced
  body and leading prose; a row with too few commas (warning, unchanged); an incomplete last row
  (warning, unchanged); a clean body (unchanged, "Nothing to fix"); idempotence (repairing twice
  equals repairing once).
- **Integration:** the endpoint renders the repaired textarea and change list, and leaves the
  receipt row untouched.
- **Fixtures use placeholder names** (`ShopAaa`, `Item One`), never real shops or products
  (CLAUDE.md §5).

## Out of scope

- Any AI-based repair (tried by the owner, unusable).
- Recovering a response truncated by `max_tokens` (see issue 02 / 29).
- Auto-parsing after the fix — the operator always reviews and presses the existing submit.
- Renaming the existing submit buttons.

## Comments

Filed 2026-09-30 from the owner's request; scope refined in discussion: fix only via algorithm,
textarea-only update with no backend write, flag unfixable cases.
