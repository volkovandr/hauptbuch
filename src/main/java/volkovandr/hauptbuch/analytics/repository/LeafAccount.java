package volkovandr.hauptbuch.analytics.repository;

/**
 * An account as a raw export labels it (reporting.md §13): by its path from the root, and — for a
 * per-person debt leaf (data-model §7) — by its owner, since the leaf's own name is cosmetic.
 *
 * @param accountId the account
 * @param path its name and every ancestor's, root first, joined by {@code :}
 * @param type its account type
 * @param currencyCode its currency
 * @param personId the owner of a debt leaf; {@code null} for any other account
 * @param personName that owner's name; {@code null} for any other account
 */
public record LeafAccount(
    long accountId,
    String path,
    String type,
    String currencyCode,
    Long personId,
    String personName) {}
