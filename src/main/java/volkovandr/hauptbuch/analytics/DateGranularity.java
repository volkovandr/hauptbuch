package volkovandr.hauptbuch.analytics;

import java.util.Locale;

/**
 * One rung of the Date ladder (reporting.md §8.2): how finely {@link DateBucket} buckets a report's
 * range, and which Postgres {@code date_trunc} unit / {@code to_char} format {@link
 * volkovandr.hauptbuch.analytics.repository.ReportQueryRepository} groups turnover by. {@link
 * DateLadder} exposes only {@link #MONTH}/{@link #WEEK} as a per-Report choice today (stage e4);
 * {@link #DAY} and {@link #YEAR} exist for the ladder's other two rungs, reached by expanding a
 * node (§9.1, §15) — not yet wired to any render path.
 */
public enum DateGranularity {
  DAY,
  WEEK,
  MONTH,
  YEAR;

  /** The {@code date_trunc(unit, ...)} field name — lowercase, matches this constant's name. */
  public String sqlUnit() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * The {@code to_char} format producing {@link DateBucket#key()}'s own text: day/week share one (a
   * bucket's own start date), month keeps its pre-e4 {@code yyyy-MM}, year is bare.
   */
  public String sqlFormat() {
    return switch (this) {
      case DAY, WEEK -> "YYYY-MM-DD";
      case MONTH -> "YYYY-MM";
      case YEAR -> "YYYY";
    };
  }
}
