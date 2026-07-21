package volkovandr.hauptbuch.debts;

/**
 * One of a person's per-currency debt-leaf accounts, paired with its currency (plan stage 8f,
 * data-model §7) — the unit a person <em>merge</em> works over. Merging folds a source person into
 * a target by moving each of these leaves' postings, per currency, onto the target's
 * matching-currency leaf (the {@code operations} reassignment path). The currency is what pairs a
 * source leaf with the right target leaf, so it travels alongside the account id.
 *
 * @param accountId the person's per-currency {@code asset} leaf
 * @param currencyCode ISO-4217 code of that leaf — the key that pairs it with the target's leaf in
 *     the same currency
 */
public record PersonLeaf(long accountId, String currencyCode) {}
