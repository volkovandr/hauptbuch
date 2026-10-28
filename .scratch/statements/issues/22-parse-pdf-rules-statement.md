# PDF (rules): Parse on a statement uploaded through a rules profile

Status: ready-for-agent
Category: enhancement
Area: `statements` — `StatementPdfService`, the parse service, `statement.html`
Design: `docs/statements.md` §3.2a
Blocked by: 20, 21

## Scope
- Upload through a `pdf_rules` profile: text extracted and the account proposed as today, but
  **no pre-masking** (it mangles lines carrying an IBAN, and nothing is sent).
- *Parse* (in place of the AI send) runs the profile's **current** rules on the statement's
  **current** text, stores the TOON in `parse_raw` and seeds the header and lines through the
  existing decoder — Re-seed, the editable TOON and the line grid unchanged. No tokens, no cost
  (those fields stay null and the cost line is not shown). No booking found ⇒ `failed` with that
  reason in `parse_error`.
- *Parse* may run again (after a profile or text fix) and replaces the TOON, header and lines; it is
  refused while any line is matched, like Re-seed.

## Done when
A bank B-style PDF statement (placeholder fixture) uploaded through a rules profile parses to lines
and header without any AI call; re-parse after a rule change replaces them; re-parse is refused
with a matched line; MockMvc acceptance covers these; `./gradlew check` is green.

## Comments
2026-10-28 — implemented on `claude/determined-keller-pl1848`, awaiting the owner's confirmation.
`StatementPdfService.create` keeps a rules profile's text unmasked; `StatementParseService.parse`
dispatches on the profile: a rules profile runs `ParseRuleEngine` on the current rules and text
(no claim, no AI settings), decodes the TOON and stores it through `StatementParseResults.applyRules`
→ `StatementRepository.markParsedByRules` (clears any earlier AI usage). `statement.html` shows
*Parse with rules* on `new`/`failed`/`processed` and hides the cost line. Choices inside the design:
- A parse that fails (no booking, rules that cannot run, no text) goes through `markFailed`; on an
  already-processed statement that leaves its old lines in place with the state `failed` and the
  reason shown, so nothing is lost; the next successful parse replaces them.
- The matched-line refusal is the same "Unmatch every line…" message as Re-seed.
