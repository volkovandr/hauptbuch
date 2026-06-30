package volkovandr.hauptbuch.shared;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.util.Locale;
import java.util.Objects;
import org.joda.money.Money;

/**
 * German display and parse for monetary values (register §2.9, NFR-08).
 *
 * <p>Numbers are formatted German-style — {@code .} groups thousands and {@code ,} is the decimal
 * separator ({@code 1.234,56}). The <em>base</em> currency is rendered <strong>bare</strong> (just
 * the number); a non-base currency carries its symbol after the number ({@code 1.234,56 CHF}). The
 * register never aggregates across currencies, so a value always knows its own currency and whether
 * it is base.
 *
 * <p>This is the single cross-cutting formatting utility (plan stage 4): it lives in {@code shared}
 * because display formatting is a concept the whole UI shares, and it does not depend on any
 * feature module. The base currency is passed in by the caller (resolved from the {@code settings}
 * entity) rather than read here, to keep this a pure, container-free utility.
 */
public final class MoneyFormat {

  /** German locale: {@code .} grouping separator, {@code ,} decimal separator. */
  private static final Locale GERMAN = Locale.GERMANY;

  private MoneyFormat() {}

  /**
   * Render {@link Money} for display: German-formatted to the currency's minor units, bare when it
   * is the base currency and suffixed with the currency symbol otherwise.
   *
   * @param money the value to render
   * @param baseCurrencyCode ISO-4217 code of the book's base currency (from {@code settings})
   */
  public static String display(Money money, String baseCurrencyCode) {
    Objects.requireNonNull(money, "money");
    int minorUnits = money.getCurrencyUnit().getDecimalPlaces();
    String number = number(money.getAmount(), minorUnits);
    if (money.getCurrencyUnit().getCode().equals(baseCurrencyCode)) {
      return number;
    }
    return number + " " + money.getCurrencyUnit().getSymbol(GERMAN);
  }

  /**
   * Format a bare decimal in German style with a fixed number of fraction digits, no currency. Used
   * for input field values and non-money quantities.
   *
   * @param amount the value to format
   * @param fractionDigits exact number of digits after the decimal separator
   */
  public static String number(BigDecimal amount, int fractionDigits) {
    Objects.requireNonNull(amount, "amount");
    DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(GERMAN));
    format.setMinimumFractionDigits(fractionDigits);
    format.setMaximumFractionDigits(fractionDigits);
    format.setGroupingUsed(true);
    return format.format(amount);
  }

  /**
   * Parse German-formatted user input back to a {@link BigDecimal}. Tolerant of present or absent
   * grouping separators ({@code 1.234,56} or {@code 1234,56} or {@code 50}).
   *
   * @param input the user-entered text
   * @throws NumberFormatException if the input is not a parseable German-formatted number
   */
  public static BigDecimal parse(String input) {
    Objects.requireNonNull(input, "input");
    String trimmed = input.trim();
    DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(GERMAN));
    format.setParseBigDecimal(true);
    ParsePosition position = new ParsePosition(0);
    Object parsed = format.parse(trimmed, position);
    if (parsed == null || position.getIndex() != trimmed.length()) {
      throw new NumberFormatException("Not a German-formatted number: \"" + input + "\"");
    }
    return (BigDecimal) parsed;
  }
}
