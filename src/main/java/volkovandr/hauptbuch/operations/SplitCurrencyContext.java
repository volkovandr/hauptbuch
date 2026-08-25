package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The resolved cross-currency state of a multi-line entry header (register §3.8a/§3.10, issue
 * receipts/23) — what {@link SplitCurrencyService#resolve} works out once and both entry surfaces
 * then read: the register's split panel and the receipt post-process editor.
 *
 * <p>The two rates are derived from the header <em>totals</em>, not from the rate feed: the
 * operator's funding and base totals are the frozen facts the commit path will book (data-model
 * §6.4), so every derived line amount and every remaining readout has to be proportional to them or
 * the columns would not reach zero together. The rate feed only ever <em>proposes</em> a blank
 * total (see {@link SplitCurrencyService#proposeTotals}); once a number is on the header, that
 * number rules.
 *
 * <p>For a single-currency header both rates are zero and the derived helpers return {@code ""} —
 * there is nothing to convert, and the fragments hang their whole cross-currency chrome off {@link
 * #cross()}.
 *
 * @param cross whether the spending currency differs from the funding leg's
 * @param fundingCurrencyCode the funding account's currency
 * @param spendingCurrencyCode the currency the lines are entered in
 * @param baseCurrencyCode the book's base currency (the funding leg's when none is set)
 * @param neitherIsBase whether a separate base-total field is needed
 * @param fundingTotal the funding-currency total as a number
 * @param baseTotal the base-currency total as a number — the funding or spending total itself when
 *     that leg already is the base currency
 * @param rateSpendingToFunding funding units per 1 spending unit, from the totals
 * @param rateSpendingToBase base units per 1 spending unit, from the totals
 */
public record SplitCurrencyContext(
    boolean cross,
    String fundingCurrencyCode,
    String spendingCurrencyCode,
    String baseCurrencyCode,
    boolean neitherIsBase,
    BigDecimal fundingTotal,
    BigDecimal baseTotal,
    BigDecimal rateSpendingToFunding,
    BigDecimal rateSpendingToBase) {

  /** German entry is to the minor unit; two places covers EUR/CHF/USD. */
  private static final int FRACTION_DIGITS = 2;

  /** The untouched single-currency header: nothing to derive, no extra readouts. */
  static SplitCurrencyContext singleCurrency(String fundingCurrencyCode) {
    return new SplitCurrencyContext(
        false,
        fundingCurrencyCode,
        fundingCurrencyCode,
        fundingCurrencyCode,
        false,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  /** A line's funding-currency equivalent as a German string; {@code ""} when there is none. */
  public String derivedFunding(BigDecimal spendingMagnitude) {
    return derived(spendingMagnitude, rateSpendingToFunding);
  }

  /** A line's base-currency equivalent as a German string; {@code ""} when there is none. */
  public String derivedBase(BigDecimal spendingMagnitude) {
    return derived(spendingMagnitude, rateSpendingToBase);
  }

  /** The amount that hits the funding account: converted when cross, the spending net otherwise. */
  public BigDecimal fundingNet(BigDecimal spendingMagnitude) {
    return cross ? derivedValue(spendingMagnitude, rateSpendingToFunding) : spendingMagnitude;
  }

  /**
   * The header view the fragments render, given how much of the spending total the lines have
   * allocated so far — which is what turns the state into the per-currency {@code remaining}
   * readouts. All of them reach zero together, since one rate links them.
   */
  public SplitCurrency view(BigDecimal allocatedSpending) {
    if (!cross) {
      return SplitCurrency.singleCurrency(fundingCurrencyCode);
    }
    BigDecimal remainingFunding =
        fundingTotal.subtract(derivedValue(allocatedSpending, rateSpendingToFunding));
    BigDecimal remainingBase =
        baseTotal.subtract(derivedValue(allocatedSpending, rateSpendingToBase));
    return new SplitCurrency(
        true,
        fundingCurrencyCode,
        spendingCurrencyCode,
        baseCurrencyCode,
        neitherIsBase,
        MoneyFormat.number(fundingTotal, FRACTION_DIGITS),
        MoneyFormat.number(baseTotal, FRACTION_DIGITS),
        MoneyFormat.number(remainingFunding, FRACTION_DIGITS),
        MoneyFormat.number(remainingBase, FRACTION_DIGITS),
        rateSpendingToFunding.toPlainString(),
        rateSpendingToBase.toPlainString());
  }

  private String derived(BigDecimal spendingMagnitude, BigDecimal rate) {
    if (!cross || spendingMagnitude.signum() == 0) {
      return "";
    }
    return MoneyFormat.number(derivedValue(spendingMagnitude, rate), FRACTION_DIGITS);
  }

  private static BigDecimal derivedValue(BigDecimal spendingMagnitude, BigDecimal rate) {
    return spendingMagnitude.multiply(rate).setScale(FRACTION_DIGITS, RoundingMode.HALF_UP);
  }
}
