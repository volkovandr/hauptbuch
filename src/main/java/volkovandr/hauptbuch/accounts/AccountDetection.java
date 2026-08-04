package volkovandr.hauptbuch.accounts;

/**
 * The paying-account detection config on one account (data-model §13.4): the labels whose
 * appearance in a receipt's payment line seed it as the paying account, and whether it is the cash
 * account a {@code Bar}/cash line resolves to in its currency. Kept off the shared {@link Account}
 * record — which the whole app maps and constructs — so its two detection columns live in their own
 * small type, read only where the account-edit screen needs them.
 *
 * <p>The analyse worker reads {@link AccountDetectionCandidate} instead: it needs every account's
 * config at once, in the order that decides which one wins.
 *
 * @param detectionLabels the operator's comma-separated label list, or null when none is configured
 * @param cashAccount whether the account is marked as the cash account
 */
public record AccountDetection(String detectionLabels, boolean cashAccount) {}
