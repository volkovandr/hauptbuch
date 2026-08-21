# No way to tag every line item on a receipt at once

Status: needs-triage
Severity: low
Area: Receipts — post-process (split toolkit)

When a receipt has many items, adding the same tag to each one individually is tedious. (The AI
note can steer per-line tags at analysis time, but that only helps if the note was added *before*
parsing — re-parsing an already-analysed long receipt just to add a note isn't a realistic
fallback after the fact.)

Request: a bulk action in the post-process line editor to apply a tag to every currently-listed
line at once.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
