package volkovandr.hauptbuch.accounts;

/**
 * One account the paying-account detection may resolve to (data-model §13.4), carrying just enough
 * to decide: its detection labels, whether it is the cash account, and the currency the cash rule
 * matches on. Always read in the order {@link
 * volkovandr.hauptbuch.accounts.repository.AccountRepository#findDetectionCandidates} defines —
 * that ordering is what makes "first match wins" deterministic.
 *
 * <p>Separate from {@link Account}, which the whole app maps and constructs: detection needs two
 * columns the account record does not carry, and nothing here needs the rest of an account.
 *
 * @param accountId the account a match resolves to
 * @param currencyCode the account's currency, matched against the parsed receipt currency
 * @param detectionLabels the operator's comma-separated label list, or null when none is configured
 * @param cashAccount whether the account is marked as the cash account
 */
public record AccountDetectionCandidate(
    long accountId, String currencyCode, String detectionLabels, boolean cashAccount) {}
