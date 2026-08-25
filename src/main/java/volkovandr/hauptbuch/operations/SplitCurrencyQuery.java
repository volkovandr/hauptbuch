package volkovandr.hauptbuch.operations;

/**
 * The inputs to {@link SplitCurrencyService#resolve} — bundled into one record rather than five
 * same-typed strings that are trivial to transpose, mirroring {@code
 * ledger.CrossCurrencyFieldsQuery}.
 *
 * @param fundingCurrencyCode the funding leg's currency, already resolved by the caller: an
 *     account's own, or the transaction currency when a person funds the whole entry
 * @param spendingCurrencyCode the currency the lines are entered in; null/blank means no override,
 *     i.e. single-currency
 * @param total the spending-currency total as typed
 * @param fundingTotal the funding-currency total as typed
 * @param baseTotal the base-currency total as typed — ignored when a leg already is the base
 */
public record SplitCurrencyQuery(
    String fundingCurrencyCode,
    String spendingCurrencyCode,
    String total,
    String fundingTotal,
    String baseTotal) {}
