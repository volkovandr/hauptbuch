# Repair a malformed TOON response automatically, and let the operator reset to the original

Status: resolved
Category: enhancement
Severity: medium
Area: Receipts — `ReceiptAnalyser` (parse path), `ToonRepair`, `receipt-process.html`,
`ReceiptProcessingController`, a Flyway migration on `receipt`

Raised 2026-10-11 by the owner. Issue 33 added `ToonRepair` as a manual "Fix TOON automatically"
button. The repair is deterministic and AI-free, so it can run on its own when the model's response
fails to decode; and since the response can then be changed by the app or the operator, the
operator needs to see that it was edited and to get back to what the model actually said.

## Desired behavior

### 1. Automatic repair on a failed decode

After the AI response arrives and decoding the TOON fails, run the raw response through
`ToonRepair` and decode again.

- **Pure re-decode.** Never a second AI call, never billed again; tokens and cost stay as frozen.
- **Decodes now:** the repaired text becomes the receipt's response and processing carries on as
  for any parsed receipt.
- **Still fails:** the original AI response is stored untouched (not half-repaired text) and the
  receipt stays `failed`, so the manual repair surface works from what the model said.

This supersedes issue 33's "no auto-parsing after the fix" for the failed-decode path only; the
manual button stays read-only as specified there.

### 2. Storage: copy-on-first-edit

The existing response column (`parse_raw`) always holds the **current** text, so every reader
(decoder, editor, re-seed) is unchanged. A new nullable column (e.g. `parse_raw_original`) is
written **once**, the first time the text is changed — by the auto-repair, or by the operator's
"Save the text and re-seed" — and stays `NULL` while the response is untouched.

- "Edited" is `parse_raw_original IS NOT NULL`.
- If a manual edit makes the text identical to the original again, clear the column: the receipt
  is "not edited".
- The branching lives only in the save path; no reader asks which text to use.

### 3. UI

- "ai response was edited" is appended to the cost/tokens info line when the original column is
  set — also on confirmed receipts.
- A **"Reset to the original"** button below the big text area, after "Fix TOON automatically" and
  the re-seed button. Like "Fix TOON automatically" it is **read-only**: it only loads the original
  text into the textarea (htmx fragment swap, no bespoke JS). Nothing is saved until the operator
  presses re-seed, so looking at the original and leaving the page changes nothing.
- Rename the re-seed button to **"Save the text and re-seed"**: that is the moment the text (and
  the original column) is persisted, even if the re-seed then fails and the receipt ends up
  `failed`.
- A confirmed receipt has no AI-response editor, so the reset button and re-seed cannot touch it;
  a committed-and-failed receipt cannot arise. The "edited" note is still shown there.

## Implementation notes

- **Hook point.** `ReceiptAnalyser.applyParsed` is the one funnel for the single-mode worker, the 9h
  batch poller and `reparse`. Auto-repair runs there, but **only for a fresh AI response** (worker
  and batch), not for `reparse`: the operator's own text is never silently rewritten, and they have
  the manual button.
- **Original column.** On a fresh response that only decodes after repair, `parse_raw_original`
  gets the AI's text and `parse_raw` the repaired one. On `reparse`, if `parse_raw_original` is
  empty, copy the current `parse_raw` into it before saving the edit (unless the edit equals it).
- **Failed re-seed keeps the old data.** `failUndecodable` touches only state, error, `parse_raw`,
  tokens and cost; header fields and draft lines stay. Committed and reopened receipts are not
  re-seedable (`isReSeedable`), so no committed-and-failed receipt can arise.
- **Stale comment.** `V9__receipt.sql` calls `parse_raw` "retained immutable (audit)", which
  `reparse` stopped being true of long ago. Never edit an applied migration (Flyway checksum); the
  new migration sets `comment on column receipt.parse_raw` to describe it as the current text, with
  the original in `parse_raw_original`.

## Out of scope

- AI-based repair (rejected in issue 33).
- Any change to confirmed receipts or to how the response is hidden after confirming.
- Storing what the auto-repair changed (the "edited" flag plus reset covers the need).

## Tests (per CLAUDE.md §6)

- **Unit:** analyser — repair rescues a decodable response (original kept, no second AI call);
  repair that still fails leaves the original text and `failed`; edit back to the original clears
  the column; a clean response never writes the original column.
- **Integration:** migration applies; repository round-trips the new column; the reset endpoint
  returns the original text and persists nothing; the info line shows the "edited" note.

## Comments

Filed 2026-10-11 from the owner's discussion; moved here from `potential-feature-ideas.md`.

### Implementation note (2026-10-11, owner-confirmed)

Branch `docs/auto-toon-repair-issue`. Migration `V36` adds `receipt.parse_raw_original` (and column
comments); `ReceiptParseResult.edited(body)` owns the copy-on-first-edit / clear-when-equal rule;
`ReceiptAnalyser.applyParsed` auto-repairs fresh responses (worker + batch), `reparse` does not;
`POST /receipts/{id}/reset-toon` is the read-only reset; the re-seed button is "Save the text and
re-seed"; the "AI response was edited" note sits on the telemetry line of the processed pane. The
failed view has no telemetry line, so there only the Reset button signals an edited response.
`./gradlew check` green.

Owner-tested: reset to the original, the edited note, the renamed button. The auto-repair on a
failed decode is covered by unit tests only (the owner had no failing response to try it on). A
browser posts a textarea with CRLF line endings, so `reparse` normalises them to LF before deciding
whether the text was edited.
