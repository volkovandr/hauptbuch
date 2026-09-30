package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.math.MathContext;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * A currency the recurring page's figures are in, and how it converts to base today (data-model
 * §14.4): an amount displays in its currency, followed by base in parentheses where the currency
 * differs and a rate is known.
 *
 * @param currency the amounts' currency
 * @param base the base currency
 * @param rate units of base per unit of {@code currency} today, or null when none is known
 */
record Valuation(String currency, String base, BigDecimal rate) {

  private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

  /** Amounts already in base. */
  static Valuation inBase(String base) {
    return new Valuation(base, base, BigDecimal.ONE);
  }

  /** {@code amount} in base; the rate must be known. */
  BigDecimal toBase(BigDecimal amount) {
    return amount.multiply(rate);
  }

  /** {@code amount} formatted in its currency, then in base where different. */
  String display(BigDecimal amount) {
    String nativeText = MoneyFormat.display(MoneyFactory.of(amount, currency), base);
    if (currency.equals(base) || rate == null) {
      return nativeText;
    }
    return nativeText
        + " ("
        + MoneyFormat.display(MoneyFactory.of(toBase(amount), base), base)
        + ")";
  }

  /** A summary row for {@code perYear}, per month a twelfth of it. */
  Line line(String label, int depth, String note, BigDecimal perYear) {
    return new Line(
        label,
        depth,
        note,
        display(perYear.divide(MONTHS_PER_YEAR, MathContext.DECIMAL64)),
        display(perYear));
  }
}
