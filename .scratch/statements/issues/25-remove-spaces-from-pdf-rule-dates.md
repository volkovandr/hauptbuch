# Statements: per-profile "Remove spaces from dates" option for PDF rule profiles

Status: resolved
Category: enhancement
Area: `statements` — `ParseRuleSettings`, `StatementProfile` (+ service, repository), the profile
form (`statement-profile.html`), `ParseRuleValues.convert`
Design: `docs/statements.md` §3.2a ("Values"), `docs/data-model.md` (`statement_profile`)

## Symptom
Some bank PDFs extract with a space between every character, so a date rule captures
`2 8 . 1 1 . 2 0 2 5`. With the profile's date format `dd.MM.yyyy` this is unreadable: the value is
passed through unconverted, the decoder flags the line, and the operator cannot fix it with a regex
(the captured text is the spaced one). Amounts do not suffer from this.

## Root cause
`ParseRuleValues.convert` only `strip()`s a date before `TypedValues.date(...)`, so inner whitespace
survives and `LocalDate.parse` fails. Numbers work only because `TypedValues.number` happens to remove
spaces and no-break spaces before parsing — an accident, not a rule.

## Accepted solution (owner)
A profile setting, **off by default**, so a date format that itself contains a space
(`d MMM yyyy`) keeps working on the profiles that do not tick it.

- **Form:** a checkbox **"Remove spaces from dates"** in the PDF-rules settings block of
  `statement-profile.html`, in the `field` right after *Date format* (`rulesDateFormat`, line ~177).
  Mirror the existing `rulesPeriodStartPlusOne` checkbox markup (label + `help-marker` + `<input
  type="checkbox" value="true" th:checked=...>`). Help text: "Tick this when the PDF text has spaces
  between the characters of a date (2 8 . 1 1 . 2 0 2 5). All whitespace is removed from every
  date before it is read, so leave it off if your date format contains a space."
- **Name:** `rulesDateRemoveSpaces` (Java), column `rules_date_remove_spaces` (SQL).
- **Storage:** new forward-only migration `V41__statement_profile_rules_date_remove_spaces.sql`:
  `alter table statement_profile add column rules_date_remove_spaces boolean;` (nullable like the
  other `rules_` columns; NULL/false = off — the code uses `Boolean.TRUE.equals(...)`). Add the
  column to the `statement_profile` DDL sketch in `docs/data-model.md` (next to
  `rules_period_start_plus_one`).
- **Plumbing, following `rulesPeriodStartPlusOne` at every site** (grep it): `StatementProfile`
  record component + javadoc `@param` + the settings conversion at its line ~115;
  `StatementProfileService` (line ~265, building `ParseRuleSettings`, and wherever the form is
  bound/saved); `StatementProfileRepository` (select list, insert columns/values, update, params);
  `ParseRuleSettings` gains `boolean dateRemoveSpaces` (javadoc `@param`). Update every
  `new StatementProfile(...)` / `new ParseRuleSettings(...)` call in tests (33 sites across
  `StatementProfileServiceTest`, `StatementParseServiceTest`, `ParseRuleEngineTest`,
  `StatementCsvParserTest`, and the integration tests `StatementParseRepositoryIntegrationTest`,
  `StatementRepositoryIntegrationTest`, `StatementProfileRuleRepositoryIntegrationTest`) —
  mechanical, add `false`.
- **Behaviour:** in `ParseRuleValues.convert`, when the field is in `DATE_FIELDS` and
  `settings.dateRemoveSpaces()`, remove **all** whitespace from the captured value before
  `TypedValues.date` — every Unicode whitespace including NBSP and narrow NBSP (a private constant
  `Pattern` of `[\p{javaWhitespace}\p{Z}]+`, replaced with ""). This single point serves header and
  line dates; the `valueDate` default from `bookingDate` works on raw text and needs no change.
  Setting off ⇒ behaviour exactly as today.
- Leave `TypedValues` (shared with the CSV path) and the numeric fields untouched. A value that still
  cannot be parsed after removal is returned as captured (existing behaviour).
- **Docs:** one sentence in `docs/statements.md` §3.2a "Values" (after the date-format mention): the
  optional *Remove spaces from dates* setting strips all whitespace from a captured date before it is
  read, for PDFs that space out their characters. No changelog entry.

## Tests to add
- `ParseRuleEngineTest` (unit; existing style, placeholder data): with the setting on, a booking date
  captured as `2 8 . 1 1 . 2 0 2 5` gives `bookingDate: 2025-11-28` and the defaulted `valueDate`;
  header `periodStart`/`periodEnd` with inner spaces and a no-break space convert; with the setting
  **off** the same spaced date is passed through unconverted; a date still invalid after removal is
  passed through.
- `StatementProfileServiceTest`: the flag reaches `ParseRuleSettings` (true and null → false).
- `StatementProfileRuleRepositoryIntegrationTest` (or the profile repository round-trip it already
  has): the column round-trips on insert and update.
- MockMvc profile-form acceptance, if the existing `rulesPeriodStartPlusOne` has one — mirror it for
  the new checkbox (rendered, saved, ticked on reload).

## Done when
`./gradlew check` is green (run `spotlessApply` first, then the individual quality tasks). Do not
mark this issue resolved until the owner has verified it on a real statement.
