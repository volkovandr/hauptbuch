# The register and People view should show the currency sign, not the ISO code

Status: needs-triage
Severity: low
Area: Money display (`shared/MoneyFormat`) × register (§2.9) × People view

A non-base amount is currently rendered with a **currency code** where a **sign** is wanted:
`1.234,56 CHF` instead of `1.234,56 ₣`, `9,50 USD` instead of `9,50 $`. The request is to prefer
the configured sign and fall back to the ISO code only when no sign is set.

> Display currency signs instead of the currency code. Only use the currency code when the sign is
> not configured.

## Where it comes from

`MoneyFormat.display` suffixes a non-base value with `money.getCurrencyUnit().getSymbol(GERMAN)`
(`shared/MoneyFormat.java`). Joda-Money's `getSymbol(Locale)` falls back to the **ISO code** for any
currency the JVM's locale data has no glyph for (and returns a locale-specific glyph, not necessarily
the one the book wants) — so what shows is inconsistent and often the bare code.

The book already carries its **own** sign: the seeded `currency` table has a nullable `symbol`
column, surfaced on the `Currency` record (`ledger/Currency.java`, `symbol` — "display symbol, e.g.
`€`; nullable"). That is the configured sign the request refers to, and it is the source of truth we
should format from, not Joda's locale glyph.

## Desired behaviour

- Non-base value → suffix the **book's configured `currency.symbol`** (`1.234,56 ₣`).
- `currency.symbol` is `NULL` (sign not configured) → fall back to the **ISO code** (`1.234,56 CHF`),
  i.e. today's worst case becomes the explicit fallback, not the default.
- Base currency → still rendered **bare** (unchanged — register §2.9).

## Scope / things to settle when picking this up

- `MoneyFormat` is deliberately a pure, container-free utility that takes only the base-currency
  **code** today; it has no access to the `currency` table. Threading the per-currency **sign**
  through to it (a lookup map, or resolving the sign at the call sites and passing it in) is the main
  design decision — keep the utility pure and don't have it reach into a repository.
- Sweep every money render, not just the register: the **People view** balances/gloss
  (`people.html`, `debts` module) is called out explicitly, plus anywhere else `MoneyFormat.display`
  or a raw code suffix is used.
- Confirm the seed data actually sets `symbol` for the currencies in use (EUR `€`, CHF `₣`, USD `$`,
  …) so the sign path is the one normally taken.
- Update the `MoneyFormat` Javadoc (currently says "carries its symbol after the number
  `1.234,56 CHF`" — that example is exactly the fallback case, not the intended default) and register
  §2.9 to state the sign-with-code-fallback rule.

## Comments
