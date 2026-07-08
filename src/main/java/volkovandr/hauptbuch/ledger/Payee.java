package volkovandr.hauptbuch.ledger;

import java.time.OffsetDateTime;

/**
 * An external counterparty / merchant (data-model §3.4). Metadata on a transaction, <em>not</em> an
 * account — it drives "spend by payee" and is a stable id for the rules engine and learned
 * merchant→category mappings. Its own table (rather than a free string) gives that stable identity.
 *
 * <p>A payee is Name + optional City + optional Country (register §3.4, plan stage 7b): the
 * picker's match key is the normalised concatenation of the three, and the create-new parser fills
 * city and country from the typed string. City is free text; {@code countryCode} references the
 * seeded {@code country} list.
 *
 * @param payeeId surrogate PK; null for a not-yet-persisted payee
 * @param name display name
 * @param city free-text city; nullable
 * @param countryCode ISO-3166 alpha-3 country code; nullable
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Payee(
    Long payeeId, String name, String city, String countryCode, OffsetDateTime deletedAt) {}
