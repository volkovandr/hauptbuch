package volkovandr.hauptbuch.shared;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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
   * Parse user-entered amount text back to a {@link BigDecimal}, leniently accepting both {@code .}
   * and {@code ,} as the decimal separator (owner decision, 2026-07-09): the <em>last</em>
   * separator in the string is the decimal point, and any earlier separators are grouping and are
   * dropped. So {@code 15.50}, {@code 15,50}, {@code 1.234,56} and {@code 1,234.56} all parse
   * correctly, and a value with no separator is a whole number ({@code 1234}). An optional leading
   * sign is honoured ({@code -}/{@code +}); the register still <em>displays</em> German ({@link
   * #number}).
   *
   * @param input the user-entered text
   * @throws NumberFormatException if the input is not a parseable number
   */
  public static BigDecimal parse(String input) {
    Objects.requireNonNull(input, "input");
    String trimmed = input.trim();
    int decimalPos = Math.max(trimmed.lastIndexOf('.'), trimmed.lastIndexOf(','));
    String normalized;
    if (decimalPos < 0) {
      normalized = trimmed; // no separator at all — a whole number
    } else {
      String integerPart = stripSeparators(trimmed.substring(0, decimalPos));
      String fractionPart = trimmed.substring(decimalPos + 1);
      normalized = fractionPart.isEmpty() ? integerPart : integerPart + "." + fractionPart;
    }
    // A bad value (letters, stray separators) throws NumberFormatException from BigDecimal
    // directly.
    return new BigDecimal(normalized);
  }

  /** Drop grouping separators (both {@code .} and {@code ,}) from the integer part. */
  private static String stripSeparators(String integerPart) {
    return integerPart.replace(".", "").replace(",", "");
  }
}
