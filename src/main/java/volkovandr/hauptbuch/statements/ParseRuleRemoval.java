package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The remove rules of a PDF (rules) profile (statements.md §3.2a): cut whole lines — the matching
 * ones included — out of the statement text, rule by rule in list order. Letterheads, page headers
 * and footers go before any line is read as a booking.
 */
final class ParseRuleRemoval {

  private ParseRuleRemoval() {}

  /** Apply every remove rule among {@code rules} to {@code lines}, in place. */
  static void apply(List<String> lines, List<ParseRule> rules) {
    for (ParseRule rule : rules) {
      switch (rule.kind()) {
        case ParseRule.REMOVE_BEFORE -> before(lines, Pattern.compile(rule.pattern()));
        case ParseRule.REMOVE_FROM -> from(lines, Pattern.compile(rule.pattern()));
        case ParseRule.REMOVE_BETWEEN ->
            between(lines, Pattern.compile(rule.pattern()), Pattern.compile(rule.patternEnd()));
        default -> {
          // header, set and booking rules remove nothing
        }
      }
    }
  }

  /** Cut from the start to the first line matching {@code start}. */
  private static void before(List<String> lines, Pattern start) {
    int first = indexOf(lines, start, 0);
    if (first >= 0) {
      lines.subList(0, first + 1).clear();
    }
  }

  /** Cut from the last line matching {@code end} to the end. */
  private static void from(List<String> lines, Pattern end) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (end.matcher(lines.get(i)).find()) {
        lines.subList(i, lines.size()).clear();
        return;
      }
    }
  }

  /** Cut every run from a line matching {@code start} to the next matching {@code end}. */
  private static void between(List<String> lines, Pattern start, Pattern end) {
    int from = indexOf(lines, start, 0);
    while (from >= 0) {
      int to = indexOf(lines, end, from + 1);
      if (to < 0) {
        return; // an A with no B after it stays
      }
      lines.subList(from, to + 1).clear();
      from = indexOf(lines, start, from);
    }
  }

  private static int indexOf(List<String> lines, Pattern pattern, int from) {
    for (int i = from; i < lines.size(); i++) {
      if (pattern.matcher(lines.get(i)).find()) {
        return i;
      }
    }
    return -1;
  }
}
