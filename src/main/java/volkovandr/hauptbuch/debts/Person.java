package volkovandr.hauptbuch.debts;

import java.time.OffsetDateTime;

/**
 * A contact; owns their per-person debt accounts (data-model §7, §3.3). One person may have many
 * accounts — one per currency (Max-EUR, Max-CHF, …) — each a standalone `asset` leaf linked via
 * {@link AccountOwner}. Grouping is the {@code account_owner → person} link, never by naming.
 *
 * <p>Soft-delete is reversible. A soft-deleted person is hidden from pickers but keeps all history
 * and is revived by confirmation when the name is re-entered.
 *
 * @param personId surrogate PK; null for a not-yet-persisted person
 * @param name display name; duplicates allowed, disambiguated in pickers
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Person(Long personId, String name, OffsetDateTime deletedAt) {}
