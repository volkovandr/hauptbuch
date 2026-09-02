# `CurrencyPickerAdvice` runs an uncached `settings` query on every MVC request

Status: needs-triage
Category: enhancement
Severity: low
Area: `ledger` — `CurrencyPickerAdvice`, `SettingsService`

## What prompted this

A branch review of the QIF import work (`import/qif-plan`) flagged this in the
currency-picker-default change (`84d63f1 feat: currency pickers default to the book base currency`).
Nothing is broken — it is a standing per-request cost that should be retired when caching is
introduced.

## The shape

`CurrencyPickerAdvice` is a global `@ControllerAdvice` whose `@ModelAttribute("baseCurrencyCode")`
runs before **every** handler in **every** `@Controller`:

```java
@ModelAttribute("baseCurrencyCode")
String baseCurrencyCode() {
  return settingsService.baseCurrency().orElse(null);
}
```

`SettingsService.baseCurrency()` → `SettingsRepository.load()` issues
`select base_currency, display_name from settings where settings_id = 1` with **no caching**. So
every request — including high-frequency htmx fragment endpoints (register refresh, balance
panels, import review re-renders) — pays a DB round-trip to supply a purely cosmetic picker
default.

The value is a near-perfect cache candidate:

- **Write-once and immutable.** `settings.base_currency` is required before any transaction and
  cannot change thereafter (data-model §3.8; CLAUDE.md §4). It can only ever transition
  `null → set` once, on the first-run screen.
- Already read on other hot paths — `LedgerService` calls `baseCurrency()` before recording every
  transaction — so a cache would help more than just the picker.

## Why it is filed, not fixed

CLAUDE.md is deliberate about deferring caches ("Add a cache only when *measured* slow" — §4; "No
materialized balances… Compute on the fly"). A one-off cache here, ahead of any measured caching
strategy, would be a second way to do something. The owner's intent is to introduce caching
"at some point" as its own piece of work — this issue is the note so the `settings` read is on
that list when it happens.

## Suggested direction (not decided — needs triage)

When a caching layer lands, memoize the base currency (and probably the whole single `settings`
row) with invalidation on the one write path (`SettingsService.setBaseCurrency` /
`updateDisplayName` / the AI-settings writers). Until then, leave `CurrencyPickerAdvice` as is — it
is correct, just not free.

**Done when:** `CurrencyPickerAdvice` / `SettingsService.baseCurrency()` no longer issues a SQL
query per MVC request, and the base currency is served from cache with correct invalidation.

## Comments

Filed 2026-09-02 from the `import/qif-plan` branch review (finding 1 of 4; the other three are
tracked separately or already documented decisions). Not urgent.
