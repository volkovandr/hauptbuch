package volkovandr.hauptbuch.importer;

/**
 * Sign evidence for one Money category path (import.md §5.2; plan d1): across every staged line on
 * that path in the campaign, how many carry a positive amount and how many a negative one. Staging
 * writes category legs in Hauptbuch's sign convention (the negation of Money's {@code $}/{@code T}
 * — see {@link ImportPosting}), so a <em>debit</em>-heavy path is money spent (an expense) and a
 * <em>credit</em>-heavy path is money received (an income). Shown beside each map row so the owner
 * can tell at a glance which category — and which type — a path belongs to.
 *
 * @param moneyPath the full Money category path — the map key (import.md §5.2)
 * @param debitLineCount staged lines on this path with a positive amount (spend)
 * @param creditLineCount staged lines on this path with a negative amount (receipt)
 */
public record ImportCategorySignEvidence(
    String moneyPath, long debitLineCount, long creditLineCount) {}
