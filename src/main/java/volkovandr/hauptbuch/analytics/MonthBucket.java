package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One column of the Date dimension at month granularity (reporting.md §8.2, stage a — the full
 * year/month/day and year/week/day ladder is stage e). {@code effectiveStart}/{@code effectiveEnd}
 * are the month clipped to the Report's resolved range; {@link #partial} is true when the range cut
 * the month short, which the header labels ({@code "Sep 2026 (to 12th)"}) rather than leaving an
 * unexplained short bar.
 */
record MonthBucket(
    YearMonth month, LocalDate effectiveStart, LocalDate effectiveEnd, boolean partial) {

  private static final DateTimeFormatter MONTH_YEAR =
      DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

  /** Every calendar month overlapping {@code [start, end]}, clipped and flagged for partiality. */
  static List<MonthBucket> monthsBetween(LocalDate start, LocalDate end) {
    List<MonthBucket> buckets = new ArrayList<>();
    YearMonth month = YearMonth.from(start);
    YearMonth last = YearMonth.from(end);
    while (!month.isAfter(last)) {
      LocalDate monthStart = month.atDay(1);
      LocalDate monthEnd = month.atEndOfMonth();
      LocalDate effectiveStart = monthStart.isBefore(start) ? start : monthStart;
      LocalDate effectiveEnd = monthEnd.isAfter(end) ? end : monthEnd;
      boolean partial = !effectiveStart.equals(monthStart) || !effectiveEnd.equals(monthEnd);
      buckets.add(new MonthBucket(month, effectiveStart, effectiveEnd, partial));
      month = month.plusMonths(1);
    }
    return buckets;
  }

  /** The display key ({@code "2026-09"}) — stable, sortable, used to correlate SQL rows. */
  String key() {
    return month.toString();
  }

  /**
   * The header label, partial-annotated when the range cut this bucket short (§8.2): the start when
   * the first bucket starts mid-month ({@code "Jan 2026 (from 15th)"}), the end when the last
   * bucket is cut short ({@code "Sep 2026 (to 12th)"}), or both when a range fits entirely inside
   * one month ({@code "Jan 2026 (10th–20th)"}).
   */
  String label() {
    String base = month.format(MONTH_YEAR);
    boolean startClipped = !effectiveStart.equals(month.atDay(1));
    boolean endClipped = !effectiveEnd.equals(month.atEndOfMonth());
    if (startClipped && endClipped) {
      return base + " (" + ordinalDay(effectiveStart) + "–" + ordinalDay(effectiveEnd) + ")";
    }
    if (endClipped) {
      return base + " (to " + ordinalDay(effectiveEnd) + ")";
    }
    if (startClipped) {
      return base + " (from " + ordinalDay(effectiveStart) + ")";
    }
    return base;
  }

  private static String ordinalDay(LocalDate date) {
    int day = date.getDayOfMonth();
    return day + ordinalSuffix(day);
  }

  /** English ordinal suffix — {@code 11th}/{@code 12th}/{@code 13th} are the teen exception. */
  private static String ordinalSuffix(int day) {
    if (day % 100 >= 11 && day % 100 <= 13) {
      return "th";
    }
    return switch (day % 10) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }
}
