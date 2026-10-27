package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Turning what a parse rule captured into the TOON's own convention — ISO dates, signed dot-decimal
 * amounts — using the profile's settings (statements.md §3.2a). The digits and the date are read by
 * {@link TypedValues}, as the CSV path reads them; a value that will not convert is returned as
 * captured, so the decoder turns its line into a problem the operator sees.
 */
final class ParseRuleValues {

  private static final Set<String> DATE_FIELDS =
      Set.of("periodStart", "periodEnd", "bookingDate", "valueDate");

  private static final Set<String> NUMBER_FIELDS =
      Set.of("openingBalance", "closingBalance", "amount", "originalAmount", "originalRate");

  private static final String MINUS = "-";

  private ParseRuleValues() {}

  /** The profile's date pattern, read the way the CSV dialect reads its own. */
  static DateTimeFormatter dateFormat(String pattern) {
    return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
  }

  /**
   * The captured {@code raw} value of {@code field}, converted: an ISO date, a plain signed number
   * (kept as text, so the decoder reads it exactly and never through a binary float), or the
   * stripped text.
   */
  static String convert(
      String field, String raw, ParseRuleSettings settings, DateTimeFormatter dateFormat) {
    String text = raw.strip();
    try {
      if (DATE_FIELDS.contains(field)) {
        LocalDate date = TypedValues.date(dateFormat, text, field);
        return date == null ? text : date.toString();
      }
      if (NUMBER_FIELDS.contains(field)) {
        return number(text, settings);
      }
    } catch (RowProblem e) {
      return text;
    }
    return text;
  }

  /** The ISO date one day after {@code isoDate}, or the value unchanged when it is not a date. */
  static String nextDay(String isoDate) {
    try {
      return LocalDate.parse(isoDate).plusDays(1).toString();
    } catch (DateTimeParseException e) {
      return isoDate;
    }
  }

  private static String number(String text, ParseRuleSettings settings) {
    String digits = text;
    String thousands = settings.thousandsSeparator();
    if (thousands != null && !thousands.isEmpty()) {
      digits = digits.replace(thousands, "");
    }
    boolean negative;
    if (ParseRuleSettings.MINUS_LEADING.equals(settings.minusPosition())) {
      negative = digits.startsWith(MINUS);
      digits = negative ? digits.substring(1) : digits;
    } else {
      negative = digits.endsWith(MINUS);
      digits = negative ? digits.substring(0, digits.length() - 1) : digits;
    }
    if (digits.contains(MINUS)) {
      return text; // a minus on the side the profile does not print it is not read as money out
    }
    BigDecimal value = TypedValues.number(settings.decimalSeparator().charAt(0), digits, "amount");
    if (value == null) {
      return text;
    }
    return (negative ? value.negate() : value).toPlainString();
  }
}
