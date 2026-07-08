package volkovandr.hauptbuch.ledger;

/**
 * A payee parsed from the picker's create-new string (register §3.4, plan stage 7b) — the fields
 * the pre-filled mini-form shows before the user confirms. Produced by {@link
 * PayeeService#parseCreateNew}; both {@code city} and {@code countryCode} are nullable (many payees
 * have neither).
 *
 * @param name display name (the first typed segment; never blank)
 * @param city free-text city; null when none was given
 * @param countryCode ISO-3166 alpha-3 code, resolved from the last segment against the country
 *     aliases; null when the last segment matched no country
 */
public record PayeeDraft(String name, String city, String countryCode) {}
