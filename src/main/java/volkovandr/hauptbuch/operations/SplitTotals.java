package volkovandr.hauptbuch.operations;

/**
 * The cross-currency header's two totals after {@link SplitCurrencyService#proposeTotals} has
 * filled in whatever was blank (issue receipts/23, decision 6). Either may still be blank or null:
 * a leg with no stored rate on or before the date yields no proposal, and the Confirm gate blocks
 * until the operator supplies the number rather than the app inventing one.
 *
 * @param fundingTotal what comes off the paying account, in its own currency
 * @param baseTotal the base-currency figure that freezes the conversion; only ever filled when
 *     neither leg is the base currency
 */
public record SplitTotals(String fundingTotal, String baseTotal) {}
