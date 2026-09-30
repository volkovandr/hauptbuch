package volkovandr.hauptbuch.receipts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an item name into a well-formed TOON cell (issue receipt-processing/33): a name that
 * already is a correctly quoted string is left alone; one holding a comma or a quote is stripped of
 * a stray outer quote pair, has its quotes escaped as {@code \"} and is wrapped in quotes.
 */
final class ToonNames {

  private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
  // A valid escape is kept as is; a lone quote or backslash gets escaped.
  private static final Pattern ESCAPE_OR_LONE = Pattern.compile("\\\\[\"\\\\]|[\"\\\\]");

  private ToonNames() {}

  static String wellFormed(String name) {
    if (QUOTED.matcher(name).matches() || !(name.contains(",") || name.contains("\""))) {
      return name;
    }
    String inner = name;
    if (inner.length() >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) {
      inner = inner.substring(1, inner.length() - 1);
    }
    return '"'
        + ESCAPE_OR_LONE
            .matcher(inner)
            .replaceAll(
                found ->
                    Matcher.quoteReplacement(
                        found.group().length() == 2 ? found.group() : "\\" + found.group()))
        + '"';
  }
}
