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
