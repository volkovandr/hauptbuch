package volkovandr.hauptbuch.ledger;

/**
 * A country the payee picker knows about — a row of the seeded {@code country} table (register
 * §3.4, plan stage 7b). Like {@code currency}, the book seeds only the countries in use, so this is
 * a short, stable, offline list; the natural key is the ISO-3166 alpha-3 {@code code}.
 *
 * <p>Its role is the create-new parser (register §3.4): the last segment of a typed payee string is
 * validated against the country aliases, which is what tells a city from a country. The canonical
 * {@code name} is also what a payee's country renders as.
 *
 * @param code ISO-3166 alpha-3 code, e.g. {@code DEU}
 * @param name canonical English display name, e.g. {@code Germany}
 */
public record Country(String code, String name) {}
