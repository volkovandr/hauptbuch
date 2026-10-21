# Register date-range filter has no quick presets

Status: resolved
Category: enhancement
Severity: minor
Area: Transaction register — filters

The register's date-range filter requires picking exact dates. Request: quick-select presets for
common ranges — e.g. "Last 12 months", "This year", "This month" — alongside the existing custom
range.

## Accepted solution (owner's decisions, 2026-10-18)
Next to the register's From / To date fields (`fromDate` / `toDate` in `register.html`), a row of
quick-select buttons. Each one sets the dates and applies the filter in one click; the other
filter fields (account picker, payee, pending only) are kept as they are.

| Button | From | To |
|--------|------|----|
| **Last 90 days** | today − 89 days (today counts as one of the 90) | empty (open end) |
| **Last 365 days** | today − 364 days | empty (open end) |
| **Previous month** | 1st of the previous calendar month | last day of the previous calendar month |

- "Last N days" leaves To **empty**, so future-dated (scheduled) rows still show.
- "Previous month" is the statement workflow: a bank statement covers a calendar month.
- No "Current month" / "Current year": the owner dropped them. The old example list ("Last 12
  months", "This year", "This month") is superseded.

## Implementation notes
- No JavaScript (CLAUDE.md §1.6: no fourth JS leaf, and the existing leaves are not the place for
  this). The buttons submit the filter form with a preset value (e.g. a `name="datePreset"`
  submit button) and the server works out the dates from today's date, so each one works without JS.
- Date arithmetic lives in Java and is unit-tested, including the January case for
  "Previous month" (gives December of the previous year) and month lengths (Feb, 30/31 days).
- MockMvc acceptance in `integrationTest`: pressing a preset renders the register with the
  expected `fromDate` / `toDate` values and keeps the other filter fields.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.

Triaged 2026-10-18: enhancement, ready-for-agent. Owner fixed the preset list and the date rules above.

Resolved 2026-10-21: `DatePreset` enum + `datePreset` submit buttons on the register filter form (after Apply, so Enter still applies).

Follow-up 2026-10-21 (owner): the presets share one row with the filters, in the order Last 90 days,
Last 365 days, Previous month, From, To, Payee (shrinks first), Pending only, Apply, so the filter box
does not grow. The register's default range is now **Last 90 days** (was 12 months) — 12 months loaded
too slowly. A visually hidden Apply twin leads the form so Enter still applies the typed filter.
