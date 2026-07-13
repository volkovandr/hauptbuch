package volkovandr.hauptbuch.operations;

/**
 * The cross-currency header state of a split panel (register §3.8a/§3.10, plan stage 7d.2) —
 * bundled out of {@link SplitPanel} so that record stays legible. A split spans at most two
 * currencies (the funding account's and one spending currency), fixed once at the header; when they
 * differ the panel reveals the funding-currency total and — when neither is the book's base — the
 * base-currency total, shows each line's derived account/base equivalents read-only, and prints a
 * {@code remaining} readout in every currency in play (all reach zero together, since one rate
 * links them).
 *
 * @param crossCurrency whether the spending currency differs from the funding account's — the whole
 *     cross-currency chrome hangs off this flag ({@code false} = the untouched single-currency
 *     split)
 * @param fundingCurrencyCode the funding account's currency (the funding-total field's label)
 * @param spendingCurrencyCode the currency the lines are entered in (the spending selector's value)
 * @param baseCurrencyCode the book's base currency (the base-total / base-remaining label)
 * @param neitherIsBase whether a separate base-total field is needed (cross-currency and neither
 *     leg is the base currency); when false the base equals whichever leg is already the base
 *     currency
 * @param fundingTotal the funding-currency total field value (the frozen funding-leg magnitude)
 * @param baseTotal the base-currency total field value — pre-filled from {@code rate_as_of},
 *     confirmable (register §3.8a); shown only when {@code neitherIsBase}
 * @param remainingFunding {@code fundingTotal − Σ|derived funding|}, German-formatted
 * @param remainingBase {@code baseTotal − Σ|derived base|}, German-formatted
 * @param rateFunding the funding-per-spending rate as a machine-decimal string (a {@code .} decimal
 *     point) so the keyboard.js leaf can recompute the derived funding column and remaining live
 * @param rateBase the base-per-spending rate as a machine-decimal string, likewise for the base
 *     column and remaining
 */
public record SplitCurrency(
    boolean crossCurrency,
    String fundingCurrencyCode,
    String spendingCurrencyCode,
    String baseCurrencyCode,
    boolean neitherIsBase,
    String fundingTotal,
    String baseTotal,
    String remainingFunding,
    String remainingBase,
    String rateFunding,
    String rateBase) {

  /** The untouched single-currency split: no cross-currency chrome, no extra readouts. */
  static SplitCurrency singleCurrency(String currencyCode) {
    return new SplitCurrency(
        false, currencyCode, currencyCode, currencyCode, false, "", "", "", "", "0", "0");
  }
}
