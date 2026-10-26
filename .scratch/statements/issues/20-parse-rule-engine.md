# PDF (rules): the parse rule engine — text + rules in, TOON out

Status: ready-for-agent
Category: enhancement
Area: `statements` — new engine class, unit tier
Design: `docs/statements.md` §3.2a (authoritative); `CONTEXT.md` *Parse rule*

## Why
A long bank B statement (14 pages) never parses with the AI: the model spends its 16k output budget
on thinking and never finishes the TOON. The owner can state the conversion as a few formal rules,
so a **PDF (rules)** profile runs them locally instead (grilled 2026-10-26).

## Scope of this issue
Only the engine: a plain-Java component that takes the statement text, the ordered rules and the
profile's rule settings, and returns the TOON body in the exact shape `ToonStatementDecoder` already
reads (the `statement:` header plus the `lines[N]{…}` table, as in `StatementPromptBuilder`'s
example). No schema, no UI — those are issues 21 and 22. Also a validation entry point the editor
uses: compile each rule, reject unknown group names and groups of the wrong kind (header fields on
header rules only, line fields on set/booking rules only), a *between* rule without B, no booking
rule.

## Behaviour (owner's decisions — see §3.2a for the full text)
- Line-based `find()` matching everywhere; named groups from the fixed vocabulary.
- Order: header rules on the full text (first match per field) → remove rules in list order
  (*before A* = to the first A; *from A* = from the last A; *between A and B* = every occurrence;
  whole lines, inclusive) → per line: set rules, then booking rules, first match consumes.
- Set values hold for every following booking; a booking's own capture wins.
- Continuation lines: previous / next / off; appended to `description` with a space, or with no
  space after a line of exactly *width* characters (trailing whitespace removed) when smart space is
  on. Leftover text with nothing to attach to is dropped.
- Numbers: configured thousands separator, decimal separator, minus leading/trailing → dot decimal,
  signed. Dates: one profile format → ISO. A value that will not convert is written as captured (the
  decoder makes it a problem line). Uncaptured `valueDate` = `bookingDate`. +1 day to period start
  when set.

## Done when
Unit tests (crafted text modelled on the bank B sample — placeholder payees `ShopAaa`…, no real
bank details) cover each rule kind, the three continuation modes, smart space, both minus
positions, the +1 day, set/booking precedence, an unconvertible value, and the validation errors;
the produced TOON round-trips through `ToonStatementDecoder`; `./gradlew check` is green.
