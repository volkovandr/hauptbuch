package volkovandr.hauptbuch.importer;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a QIF file's day/month order across the whole file (import.md §4.3, slice a3). QIF
 * declares no date format and Money writes the Windows short date ({@code D26/11'2011}, with {@code
 * /}, {@code .}, {@code -} or {@code '} as separators), so the order is inferred from evidence: a
 * first component past 12 proves {@code DD/MM}, a second component past 12 proves {@code MM/DD},
 * and a file where neither ever happens is genuinely {@code AMBIGUOUS}.
 *
 * <p>The result is a <em>proposal</em> carrying its proof — the upload preview (b2) states it and
 * the owner confirms or overrides before anything is staged, because a silent day/month swap
 * corrupts every date in the campaign and looks valid for the ~68% of dates where both components
 * are ≤ 12. A file whose own dates prove <em>both</em> orders, or that carries a {@code D} line
 * that is not a date at all, is refused rather than guessed at (CLAUDE.md §0).
 */
final class QifDateFormat {

  private static final char DATE_FIELD = 'D';
  private static final int MAX_MONTH = 12;
  private static final int MAX_DAY = 31;
  private static final int NONE = -1;

  /** A {@code D}-field line: the letter, then day / month / year on {@code / . - '}. */
  private static final Pattern DATE_LINE =
      Pattern.compile("^D(\\d{1,2})[/.'-](\\d{1,2})[/.'-](\\d{2,4})$");

  private QifDateFormat() {}

  /** The inferred order of a file's dates. */
  enum Order {
    DAY_MONTH,
    MONTH_DAY,
    AMBIGUOUS
  }

  /**
   * The detected {@link Order} and, unless {@code AMBIGUOUS}, the line that proves it and that
   * line's 1-based number in the decoded text.
   *
   * @param order the inferred order
   * @param evidenceLine the verbatim {@code D} line that proves {@code order}; null when {@code
   *     AMBIGUOUS}
   * @param evidenceLineNumber that line's 1-based number in the decoded text; null when {@code
   *     AMBIGUOUS}
   */
  record Detection(Order order, String evidenceLine, Integer evidenceLineNumber) {

    /** A one-line description for the upload preview surface. */
    String describe() {
      return switch (order) {
        case DAY_MONTH -> "DD/MM, proven by `" + evidenceLine + "` on line " + evidenceLineNumber;
        case MONTH_DAY -> "MM/DD, proven by `" + evidenceLine + "` on line " + evidenceLineNumber;
        case AMBIGUOUS -> "AMBIGUOUS — no date in this file distinguishes them";
      };
    }
  }

  /** Scan every {@code D}-field line of the decoded text and infer the order (§4.3). */
  static Detection detect(String text) {
    List<String> lines = text.lines().toList();
    int dayMonthIndex = NONE;
    int monthDayIndex = NONE;
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index).strip();
      if (!isDateLine(line)) {
        continue;
      }
      DateParts parts = parse(line);
      if (parts.first() > MAX_MONTH && dayMonthIndex == NONE) {
        dayMonthIndex = index;
      }
      if (parts.second() > MAX_MONTH && monthDayIndex == NONE) {
        monthDayIndex = index;
      }
    }
    return resolve(lines, dayMonthIndex, monthDayIndex);
  }

  private static Detection resolve(List<String> lines, int dayMonthIndex, int monthDayIndex) {
    Detection dayMonth = detectionAt(lines, dayMonthIndex, Order.DAY_MONTH);
    Detection monthDay = detectionAt(lines, monthDayIndex, Order.MONTH_DAY);
    if (dayMonth != null && monthDay != null) {
      throw new QifRejectedException(
          "This file's dates contradict each other: "
              + dayMonth.evidenceLine()
              + " on line "
              + dayMonth.evidenceLineNumber()
              + " is DD/MM, but "
              + monthDay.evidenceLine()
              + " on line "
              + monthDay.evidenceLineNumber()
              + " is MM/DD.");
    }
    if (dayMonth != null) {
      return dayMonth;
    }
    if (monthDay != null) {
      return monthDay;
    }
    return new Detection(Order.AMBIGUOUS, null, null);
  }

  private static Detection detectionAt(List<String> lines, int index, Order order) {
    if (index == NONE) {
      return null;
    }
    return new Detection(order, lines.get(index).strip(), index + 1);
  }

  private static boolean isDateLine(String line) {
    return !line.isEmpty() && line.charAt(0) == DATE_FIELD;
  }

  private static DateParts parse(String line) {
    Matcher matcher = DATE_LINE.matcher(line);
    if (!matcher.matches()) {
      throw new QifRejectedException("Not a valid QIF date: \"" + line + "\".");
    }
    return new DateParts(component(matcher.group(1), line), component(matcher.group(2), line));
  }

  private static int component(String digits, String line) {
    int value = Integer.parseInt(digits);
    if (value < 1 || value > MAX_DAY) {
      throw new QifRejectedException("Not a valid QIF date: \"" + line + "\".");
    }
    return value;
  }

  /** The first two components of a {@code D} date — day and month, in whichever order. */
  private record DateParts(int first, int second) {}
}
