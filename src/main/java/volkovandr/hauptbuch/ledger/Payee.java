package volkovandr.hauptbuch.ledger;

import java.time.OffsetDateTime;

/**
 * An external counterparty / merchant (data-model §3.4). Metadata on a transaction, <em>not</em> an
 * account — it drives "spend by payee" and is a stable id for the rules engine and learned
 * merchant→category mappings. Its own table (rather than a free string) gives that stable identity.
 *
 * @param payeeId surrogate PK; null for a not-yet-persisted payee
 * @param name display name
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Payee(Long payeeId, String name, OffsetDateTime deletedAt) {}
