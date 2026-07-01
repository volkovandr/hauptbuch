package volkovandr.hauptbuch.ledger;

/**
 * A currency the book knows about — a row of the seeded {@code currency} table (data-model §3.1).
 * The book seeds only the currencies in use, not all ~180 ISO codes, so this is a short list.
 *
 * <p>Exposed for the settings UI to offer as base-currency choices on first run (plan stage 5). The
 * natural key is the ISO-4217 {@code code}; {@code minorUnits} drives money rounding and {@code
 * symbol} is nullable.
 *
 * @param code ISO-4217 code, e.g. {@code EUR}
 * @param minorUnits fractional digits (2 for EUR, 0 for JPY)
 * @param symbol display symbol, e.g. {@code €}; nullable
 * @param name human-readable name, e.g. {@code Euro}
 */
public record Currency(String code, int minorUnits, String symbol, String name) {}
