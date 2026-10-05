package volkovandr.hauptbuch.statements;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A profile's dialect settings, validated: how the bytes become text and the text becomes fields,
 * numbers and dates.
 *
 * @param charset the file's encoding
 * @param delimiter the field delimiter
 * @param quote the quote character
 * @param decimal the decimal separator, {@code ,} or {@code .}
 * @param dateFormat the booking and value date pattern
 */
record CsvDialect(
    Charset charset, char delimiter, char quote, char decimal, DateTimeFormatter dateFormat) {

  private static final String TAB_ESCAPE = "\\t";

  /**
   * The dialect of {@code profile}.
   *
   * @throws StatementFormatException naming the first setting that cannot be applied
   */
  static CsvDialect of(StatementProfile profile) {
    return new CsvDialect(
        charset(profile.csvEncoding()),
        single(profile.csvDelimiter(), "delimiter"),
        single(profile.csvQuote(), "quote"),
        decimal(profile.csvDecimalSeparator()),
        dateFormat(profile.csvDateFormat()));
  }

  private static Charset charset(String name) {
    try {
      return Charset.forName(name == null || name.isBlank() ? "UTF-8" : name.strip());
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      throw new StatementFormatException("Unknown encoding '" + name + "'.", e);
    }
  }

  /** One character; the two characters {@code \t} stand for a tab. */
  private static char single(String text, String what) {
    String value = text == null ? "" : text;
    if (TAB_ESCAPE.equals(value)) {
      return '\t';
    }
    if (value.length() != 1) {
      throw new StatementFormatException("The " + what + " must be exactly one character.");
    }
    return value.charAt(0);
  }

  private static char decimal(String text) {
    if (",".equals(text) || ".".equals(text)) {
      return text.charAt(0);
    }
    throw new StatementFormatException("The decimal separator must be , or .");
  }

  private static DateTimeFormatter dateFormat(String pattern) {
    try {
      return DateTimeFormatter.ofPattern(pattern == null ? "" : pattern, Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw new StatementFormatException("The date format '" + pattern + "' is not valid.", e);
    }
  }
}
