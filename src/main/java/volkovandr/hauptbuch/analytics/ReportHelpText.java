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
      "Which account types a measure adds up — asset, liability, income, expense, equity —"
          + " plus closed accounts and pending-review transactions. A filter narrows which"
          + " transactions or postings qualify; Scope decides what kind of account counts at all.";

  public static final String FILTER_READING_SWITCH =
      "“Transactions touching …” counts a transaction if any of its postings match."
          + " “Amounts booked to …” counts only the matching postings themselves"
          + " — the two can give different totals for the same filter.";

  public static final String MEASURE_KIND =
      "Turnover is a flow, summed over the period. Closing balance is a stock, the position at"
          + " period’s end — it can be summed across accounts but never across time, so a"
          + " total that would need that renders —.";

  public static final String LEG =
      "Net, debits and credits only apply to Turnover, and are independent of account type:"
          + " spending is always a credit, on a debit card or a credit card alike.";

  public static final String PRESENTATION_CURRENCY =
      "Base values every posting or balance in the ledger’s base currency. Account currency"
          + " shows native amounts with no conversion — only where every account contributing"
          + " to the cell shares one currency, otherwise it renders —.";

  public static final String RANGE_ENDPOINT =
      "A literal date is fixed. A relative endpoint is a unit, offset and edge — start or end"
          + " — resolved against today each time the Report is opened; the label beside it"
          + " shows what that resolves to.";

  public static final String INCLUDE_PENDING_REVIEW =
      "Recurring pre-registrations and unreviewed captures aren’t facts yet; including them"
          + " can inflate the current period. The header line above shows when this is on.";

  private ReportHelpText() {}
}
