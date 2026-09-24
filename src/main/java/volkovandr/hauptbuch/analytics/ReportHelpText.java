package volkovandr.hauptbuch.analytics;

/**
 * Static copy for the settings strip's CSS-only help markers (reporting.md §11a.7, plan stage
 * d3-5): the concepts a label can't carry, explained once here and referenced from every
 * settings-strip template via Thymeleaf's {@code T()} operator — the same idiom the strip already
 * uses for {@link RangeUnit}/{@link RangeEdge}'s own enum values — rather than inlined as a
 * template literal. The illegal-{@code —}-cell markers are their own case: their text depends on
 * which of {@link Cell.Reason} applies, so that copy lives in {@link ReportTableViewAssembler}
 * instead.
 */
// PMD's DataClass fires on any pure constants holder with no behaviour — that is exactly what this
// class is by design, the same idiom as a message-bundle class; there is no behaviour to add.
@SuppressWarnings("PMD.DataClass")
public final class ReportHelpText {

  public static final String SCOPE =
      "Which account types count toward this measure, plus closed accounts and pending-review"
          + " transactions. Filters narrow this further.";

  public static final String FILTER_READING_SWITCH =
      "“Transactions touching …” matches by transaction. “Amounts booked to …” matches by"
          + " posting — these can give different totals.";

  public static final String MEASURE_KIND =
      "Turnover is a flow over the period. Closing balance is a stock at period’s end — it is"
          + " never summed across time.";

  public static final String CLOSING_BALANCE_UNAVAILABLE =
      "A tag or payee holds no balance, so Closing balance is unavailable while one is on an"
          + " axis.";

  public static final String LEG =
      "Net, debits and credits apply only to Turnover, independent of account type.";

  public static final String PRESENTATION_CURRENCY =
      "Base converts everything to the base currency. Account currency shows native amounts,"
          + " but only where every contributing account shares one currency.";

  public static final String RANGE_ENDPOINT =
      "A fixed date, or a relative offset from today — resolved live in the label beside it.";

  public static final String INCLUDE_PENDING_REVIEW =
      "Recurring and unreviewed items aren’t facts yet, so they’re excluded by default.";

  public static final String DATE_LADDER =
      "Only affects the Date dimension, when it is on rows or columns — month or week buckets.";

  private ReportHelpText() {}
}
