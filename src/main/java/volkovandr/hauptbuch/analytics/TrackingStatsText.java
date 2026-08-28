package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Pure text helpers for the landing-page tracking-stats line (CONTEXT.md "Tracking stats"). Kept
 * separate from {@link TrackingStatsService} so the wording rules — pluralisation, the German
 * thousands separator, decimal (SI) byte units — are unit-testable without touching the ledger or
 * the filesystem.
 */
final class TrackingStatsText {

  /** SI step for byte units — decimal, not binary (owner decision, 2026-08-28). */
  private static final double STEP = 1000.0;

  private static final String[] BYTE_UNITS = {"B", "kB", "MB", "GB"};

  private TrackingStatsText() {}

  /**
   * The span from {@code from} to {@code to} in words: years and months, days dropped ({@code "2
   * years and 8 months"}, {@code "1 year and 1 month"}, {@code "3 years"}, {@code "7 months"}). A
   * span shorter than a month — or a {@code from} that is not before {@code to} — reads {@code
   * "less than a month"}.
   */
  static String duration(LocalDate from, LocalDate to) {
    if (!from.isBefore(to)) {
      return "less than a month";
    }
    Period span = Period.between(from, to);
    int years = span.getYears();
    int months = span.getMonths();
    if (years == 0 && months == 0) {
      return "less than a month";
    }
    if (years == 0) {
      return plural(months, "month");
    }
    if (months == 0) {
      return plural(years, "year");
    }
    return plural(years, "year") + " and " + plural(months, "month");
  }

  /**
   * A count with the German thousands separator and a pluralised noun: {@code count(1234,
   * "transaction")} → {@code "1.234 transactions"}, {@code count(1, "receipt")} → {@code "1
   * receipt"}.
   */
  static String count(long value, String noun) {
    return MoneyFormat.number(BigDecimal.valueOf(value), 0) + " " + noun + (value == 1 ? "" : "s");
  }

  /**
   * A byte total in decimal (SI) units to one decimal place: {@code "2,5 GB"}, {@code "847,0 MB"},
   * {@code "512,0 B"}. Very large totals stay in {@code GB}.
   */
  static String humaniseBytes(long bytes) {
    double value = bytes;
    int unit = 0;
    while (value >= STEP && unit < BYTE_UNITS.length - 1) {
      value /= STEP;
      unit++;
    }
    BigDecimal shown = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_EVEN);
    // A value like 999.97 rounds to 1000,0 at one decimal — carry it up a unit (e.g. "1,0 kB",
    // not "1.000,0 B"), unless we are already at the largest unit.
    if (shown.compareTo(BigDecimal.valueOf(STEP)) >= 0 && unit < BYTE_UNITS.length - 1) {
      shown = new BigDecimal("1.0");
      unit++;
    }
    return MoneyFormat.number(shown, 1) + " " + BYTE_UNITS[unit];
  }

  private static String plural(int amount, String noun) {
    return amount + " " + noun + (amount == 1 ? "" : "s");
  }
}
