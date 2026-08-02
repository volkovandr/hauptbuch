package volkovandr.hauptbuch.accounts;

/**
 * The paying-account detection config on one account (data-model §13.4, stage 9e): the card last-4
 * whose slips seed it, and whether it is the cash account a {@code Bar}/cash line resolves to. Kept
 * off the shared {@link Account} record — which the whole app maps and constructs — so its two
 * detection columns live in their own small type, read only where the account-edit screen and the
 * receipt analyse worker need them.
 *
 * @param cardLast4 the printed last four digits of the account's card, or null if not card-detected
 * @param cashAccount whether the account is marked as the cash account
 */
public record AccountDetection(String cardLast4, boolean cashAccount) {}
