package volkovandr.hauptbuch.analytics;

/**
 * A Report's granularity ladder (reporting.md §8.2): {@code year → month → day} or {@code year →
 * week → day}. {@link ReportSpec#dateLadder()} names the choice; {@link #bucketGranularity()} is
 * the rung the engine currently renders (the ladder's own year/day rungs are reached by expanding a
 * node — deferred past stage e4, see {@link DateGranularity}). Defaults to {@link #MONTH}, matching
 * every pre-e4 Report (reporting.md §15).
 */
public enum DateLadder {
  MONTH,
  WEEK;

  DateGranularity bucketGranularity() {
    return this == MONTH ? DateGranularity.MONTH : DateGranularity.WEEK;
  }
}
