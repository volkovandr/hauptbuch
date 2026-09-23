package volkovandr.hauptbuch.analytics;

/**
 * A Report's granularity ladder (reporting.md §8.2): {@code year → month → day} or {@code year →
 * week → day}. {@link ReportSpec#dateLadder()} names the choice; {@link #bucketGranularity()} is
 * the rung a Date axis starts at; a Date row expands in place into its days (§9.1), while the year
 * rung is not reachable yet. Defaults to {@link #MONTH}, matching every pre-e4 Report (reporting.md
 * §15).
 */
public enum DateLadder {
  MONTH,
  WEEK;

  DateGranularity bucketGranularity() {
    return this == MONTH ? DateGranularity.MONTH : DateGranularity.WEEK;
  }
}
