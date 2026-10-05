package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Reading dates and amounts out of text, for the CSV parser (a column in the profile's own
 * convention) and for the operator's typing (ISO dates, lenient German/English amounts). A value
 * that cannot be read is a {@link RowProblem} naming it; blank is {@code null}.
 */
final class TypedValues {

  private TypedValues() {}

  /** A date in the profile's pattern; blank is null. */
  static LocalDate date(DateTimeFormatter format, String text, String what) {
    if (text.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(text.strip(), format);
    } catch (DateTimeParseException e) {
      throw new RowProblem("Unreadable " + what + " '" + text + "'", e);
    }
  }

  /** A number written with the given decimal separator ({@code ,} or {@code .}); blank is null. */
  static BigDecimal number(char decimalSeparator, String text, String what) {
    if (text.isBlank()) {
      return null;
    }
    String digits = text.replace(" ", "").replace(" ", "");
    digits =
        decimalSeparator == ','
            ? digits.replace(".", "").replace(',', '.')
            : digits.replace(",", "");
    try {
      return new BigDecimal(digits);
    } catch (NumberFormatException e) {
      throw new RowProblem("Unreadable " + what + " '" + text + "'", e);
    }
  }

  /** An ISO date as typed into a date input; blank is null. */
  static LocalDate typedDate(String text, String what, String prefix) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(text.strip());
    } catch (DateTimeParseException e) {
      throw new StatementFormatException(
          prefix + "The " + what + " '" + text + "' is not a date.", e);
    }
  }

  /** An amount typed in either convention ({@link MoneyFormat#parse}); blank is null. */
  static BigDecimal typedAmount(String text, String what, String prefix) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return MoneyFormat.parse(text);
    } catch (NumberFormatException e) {
      throw new StatementFormatException(
          prefix + "The " + what + " '" + text + "' is not a number.", e);
    }
  }
}
