# PDF (rules): schema and the profile editor with Try

Status: ready-for-agent
Category: enhancement
Area: `statements` — migration, `StatementProfile`, `StatementProfileRepository`, profile screen
Design: `docs/statements.md` §3.2a; schema in `docs/data-model.md` §15
Blocked by: 20

## Scope
- **Migration:** `format` gains `pdf_rules`; the `rules_*` columns on `statement_profile`; the
  `statement_profile_rule` child table (data-model §15). The `rules_*` columns are independent of
  the `csv_*` ones even where they mean the same — owner decision (separation of concerns), no
  renames, no sharing.
- **Repository:** read/write the settings and the ordered rules (save replaces the profile's rule
  rows); round-trip tests in `integrationTest`.
- **Editor:** a third choice "PDF (rules)" beside "CSV" and "PDF (AI)". The settings (thousands and
  decimal separators, minus position, date format, continuation, smart-space width, +1 day with the
  hint "statements must not overlap — tick this if your bank prints the previous statement's closing
  balance") and an ordered rule list (kind, pattern, B for *between*; add / remove / move up-down).
  Beside it a **test text area**: *Upload PDF* extracts the text into it (the file is read in memory
  and discarded, never stored); pasting works too. A rule's regex and group names are validated as
  soon as it changes (htmx, server-side via issue 20's validation); *Try* posts text + rules and
  shows the TOON. Save is refused while invalid. Plain htmx — **no new JS leaf**.

## Done when
A rules profile can be created, edited and saved; invalid rules show their error on the rule and
block Save; Try shows the engine's TOON for uploaded or pasted text; nothing of the test text is
persisted; MockMvc acceptance covers save, validation and Try; `./gradlew check` is green.

## Comments
2026-10-27 — implemented on `claude/determined-keller-pl1848`, awaiting the owner's confirmation
together with issue 20. V40 (format `pdf_rules`, the `rules_*` columns, `statement_profile_rule`);
`StatementProfileRuleRepository`; `StatementProfileService.save(profile, rules)` validates a rules
profile through `ParseRuleEngine` and writes it and its rules in one transaction;
`ParseRuleEditorController` (live check, row buttons, PDF text load, Try) and `ParseRuleForm`.
Choices made inside the agreed design:
- `isPdf()` is true for both PDF formats (the file path); `usesParseRules()` picks the parser. Until
  issue 22 a statement uploaded through a rules profile still takes the AI parse path.
- The rules are read from the raw request (`ruleKind` / `rulePattern` / `rulePatternEnd`), never as a
  bound list — Spring would split a lone regex at its commas.
- A pattern is saved exactly as typed (a space in a regex matters); B is kept only on a *between*
  rule.
- The live check posts the form without the test text (`hx-params`), and the rules form is
  `novalidate`: htmx otherwise runs the browser's `required` check on Name and silently drops every
  check until a name is typed. Save is still validated on the server. (The CSV profile form has the
  same latent behaviour — its live preview waits for a name; left as is.)
- *Load the test text from a PDF* is its own small form below the editor — it cannot nest inside
  the profile form, and outside it the PDF is never re-sent with every check.
- Verified in headless Chromium against the running app: live check, add / move, Try, Save, reopen,
  and loading a real PDF's text.
- 2026-10-28, owner's follow-up: the group names are also shown as an always-visible, copyable
  block below the rules (rendered from `ParseRule.HEADER_FIELDS` / `LINE_FIELDS`), next to a short
  regex cheat sheet.
