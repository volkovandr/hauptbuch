package volkovandr.hauptbuch.analytics;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One column of the Date dimension, at whichever {@link DateGranularity} the Report's {@link
 * DateLadder} currently renders (reporting.md §8.2 — stage e4 generalizes stage a's month-only
 * bucket to every rung of the ladder). {@code effectiveStart}/{@code effectiveEnd} are the bucket
 * clipped to the Report's resolved range; {@link #partial} is true when the range cut the bucket
 * short, which the header labels ({@code "Sep 2026 (to 12th)"}, {@code "w/c 14 Sep 2026 (to 18
 * Sep)"}) rather than leaving an unexplained short bar. A {@link DateGranularity#DAY} bucket is
 * never partial — a single calendar day cannot itself be cut short.
 */
record DateBucket(
    DateGranularity granularity,
    LocalDate bucketStart,
    LocalDate effectiveStart,
    LocalDate effectiveEnd,
    boolean partial) {

  private static final DateTimeFormatter MONTH_YEAR =
      DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter DAY_MONTH_YEAR =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter DAY_MONTH =
      DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

  /**
   * Every bucket of {@code granularity} overlapping {@code [start, end]}, clipped and flagged for
   * partiality. Week buckets start Monday (ISO, reporting.md §8.2 — matches Postgres {@code
   * date_trunc('week')}).
   */
  static List<DateBucket> bucketsBetween(
      DateGranularity granularity, LocalDate start, LocalDate end) {
    List<DateBucket> buckets = new ArrayList<>();
    LocalDate bucketStart = bucketStartFor(granularity, start);
    LocalDate lastBucketStart = bucketStartFor(granularity, end);
    while (!bucketStart.isAfter(lastBucketStart)) {
      LocalDate bucketEnd = bucketEndFor(granularity, bucketStart);
      LocalDate effectiveStart = bucketStart.isBefore(start) ? start : bucketStart;
      LocalDate effectiveEnd = bucketEnd.isAfter(end) ? end : bucketEnd;
      boolean partial = !effectiveStart.equals(bucketStart) || !effectiveEnd.equals(bucketEnd);
      buckets.add(new DateBucket(granularity, bucketStart, effectiveStart, effectiveEnd, partial));
      bucketStart = nextBucketStart(granularity, bucketStart);
    }
    return buckets;
  }

  private static LocalDate bucketStartFor(DateGranularity granularity, LocalDate date) {
    return switch (granularity) {
      case DAY -> date;
      case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      case MONTH -> date.withDayOfMonth(1);
      case YEAR -> date.withDayOfYear(1);
    };
  }

  /**
   * This bucket's own days, clipped to its effective range — what expanding it in place reveals
   * (reporting.md §9.1, §15).
   */
  List<DateBucket> days() {
    return bucketsBetween(DateGranularity.DAY, effectiveStart, effectiveEnd);
  }

  /** This bucket clipped to the Report's range, as a range of its own — what its days cover. */
  RangeResolver.ResolvedRange effectiveRange() {
    return new RangeResolver.ResolvedRange(effectiveStart, effectiveEnd);
  }

  /** The bucket's own last day — the calendar day before {@link #nextBucketStart}. */
  LocalDate bucketEnd() {
    return bucketEndFor(granularity, bucketStart);
  }

  private static LocalDate bucketEndFor(DateGranularity granularity, LocalDate bucketStart) {
    return switch (granularity) {
      case DAY -> bucketStart;
      case WEEK -> bucketStart.plusDays(6);
      case MONTH -> YearMonth.from(bucketStart).atEndOfMonth();
      case YEAR -> bucketStart.withDayOfYear(1).plusYears(1).minusDays(1);
    };
  }

  private static LocalDate nextBucketStart(DateGranularity granularity, LocalDate bucketStart) {
    return switch (granularity) {
      case DAY -> bucketStart.plusDays(1);
      case WEEK -> bucketStart.plusWeeks(1);
      case MONTH -> bucketStart.plusMonths(1);
      case YEAR -> bucketStart.plusYears(1);
    };
  }

  /**
   * The stable, sortable key used to correlate SQL rows — mirrors {@link
   * DateGranularity#sqlFormat()}.
   */
  String key() {
    return switch (granularity) {
      case DAY, WEEK -> bucketStart.toString();
      case MONTH -> YearMonth.from(bucketStart).toString();
      case YEAR -> String.valueOf(bucketStart.getYear());
    };
  }

  /**
   * The header label, partial-annotated when the range cut this bucket short (§8.2). Month keeps
   * its pre-e4 ordinal-day-only suffix (the clipped edge is always within the base label's own
   * month); week and year use a {@code "d MMM"} suffix instead, since their clipped edge can fall
   * in a different month than the bucket's own start. Day is never partial (see the class doc) and
   * so never carries a suffix.
   */
  String label() {
    return switch (granularity) {
      case DAY -> bucketStart.format(DAY_MONTH_YEAR);
      case WEEK -> withPartialSuffix("w/c " + bucketStart.format(DAY_MONTH_YEAR), DAY_MONTH);
      case MONTH -> monthLabel();
      case YEAR -> withPartialSuffix(String.valueOf(bucketStart.getYear()), DAY_MONTH);
    };
  }

  private String monthLabel() {
    String base = bucketStart.format(MONTH_YEAR);
    LocalDate monthStart = bucketStart;
    LocalDate monthEnd = bucketEnd();
    boolean startClipped = !effectiveStart.equals(monthStart);
    boolean endClipped = !effectiveEnd.equals(monthEnd);
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

  private String withPartialSuffix(String base, DateTimeFormatter clipFormat) {
    LocalDate bucketEnd = bucketEnd();
    boolean startClipped = !effectiveStart.equals(bucketStart);
    boolean endClipped = !effectiveEnd.equals(bucketEnd);
    if (startClipped && endClipped) {
      return base
          + " ("
          + effectiveStart.format(clipFormat)
          + "–"
          + effectiveEnd.format(clipFormat)
          + ")";
    }
    if (endClipped) {
      return base + " (to " + effectiveEnd.format(clipFormat) + ")";
    }
    if (startClipped) {
      return base + " (from " + effectiveStart.format(clipFormat) + ")";
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
