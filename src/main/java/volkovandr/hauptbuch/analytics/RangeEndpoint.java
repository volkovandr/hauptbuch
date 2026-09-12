package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;

/**
 * One end of a Report's date range (reporting.md §8.1, CONTEXT.md "Range endpoint"): a literal date
 * or an expression of unit × offset × edge, resolved against "today" at render time — which is why
 * a saved Report or a main-page Frame stays fresh.
 */
public sealed interface RangeEndpoint {

  /** A fixed calendar date, independent of when the Report is rendered. */
  record Literal(LocalDate date) implements RangeEndpoint {}

  /**
   * {@code unit, offset, edge} — e.g. {@code month, -1, end} = the last day of last month; {@code
   * day, 0, start} = today. {@code offset} is 0 for the current period, negative for the past.
   */
  record Relative(RangeUnit unit, int offset, RangeEdge edge) implements RangeEndpoint {}
}
