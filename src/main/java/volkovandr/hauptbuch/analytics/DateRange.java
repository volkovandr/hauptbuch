package volkovandr.hauptbuch.analytics;

/**
 * A Report's date range as two independent {@link RangeEndpoint}s (reporting.md §8.1). Named
 * shortcuts ("Year to date", "Previous month", …) are a UI convenience that fills these two fields;
 * the engine only ever sees the resolved pair.
 */
public record DateRange(RangeEndpoint start, RangeEndpoint end) {

  /** "Year to date" (reporting.md §8.1's table): 1 Jan this year through today. */
  public static DateRange yearToDate() {
    return new DateRange(
        new RangeEndpoint.Relative(RangeUnit.YEAR, 0, RangeEdge.START),
        new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START));
  }

  /** "Last 12 months, incl. current": the start of the month 11 months ago through today. */
  public static DateRange last12Months() {
    return new DateRange(
        new RangeEndpoint.Relative(RangeUnit.MONTH, -11, RangeEdge.START),
        new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START));
  }

  /** "Previous month": the whole of last month. */
  public static DateRange previousMonth() {
    return new DateRange(
        new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.START),
        new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.END));
  }

  /** "Previous year": the whole of last calendar year. */
  public static DateRange previousYear() {
    return new DateRange(
        new RangeEndpoint.Relative(RangeUnit.YEAR, -1, RangeEdge.START),
        new RangeEndpoint.Relative(RangeUnit.YEAR, -1, RangeEdge.END));
  }

  /** "Current month only": the start of this month through today. */
  public static DateRange currentMonth() {
    return new DateRange(
        new RangeEndpoint.Relative(RangeUnit.MONTH, 0, RangeEdge.START),
        new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START));
  }
}
