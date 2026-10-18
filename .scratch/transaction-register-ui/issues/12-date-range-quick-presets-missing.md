# Register date-range filter has no quick presets

Status: needs-info
Type: improvement
Severity: minor
Area: Transaction register — filters

The register's date-range filter requires picking exact dates. Request: quick-select presets for
common ranges — e.g. "Last 12 months", "This year", "This month" — alongside the existing custom
range.

## Accepted solution (owner's decision, 2026-10-18)
Next to the register's start/end date fields (`fromDate` / `toDate` in `register.html`), a row of
quick-select buttons that fill both dates and apply the filter:

- **Last 90 days**
- **Last 365 days**
- **Current month**
- **Current year**

This replaces the example list above ("Last 12 months", "This year", "This month").

Open details (proposed defaults, waiting for the owner):
- "Last N days" ends today and starts N−1 days before it, so today counts as one of the N days.
- "Current month" / "Current year" run from the 1st of the month / 1 January to today. The
  alternative is to run to the end of the month / year, which would also show future-dated
  (scheduled) transactions.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
