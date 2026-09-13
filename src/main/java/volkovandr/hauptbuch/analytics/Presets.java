package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * The four Presets reporting.md §16 ships (code-defined, always present, non-deletable): the two
 * table Presets from stage a, plus the two chart Presets stage b adds.
 */
public final class Presets {

  /** URL slug for {@link #categoryMonthMatrix()} (reporting.md §14 — {@code /reports/preset/*}). */
  public static final String CATEGORY_MONTH_MATRIX_SLUG = "category-month-matrix";

  /** URL slug for {@link #balanceSheet()}. */
  public static final String BALANCE_SHEET_SLUG = "balance-sheet";

  /** URL slug for {@link #netWorthOverTime()}. */
  public static final String NET_WORTH_OVER_TIME_SLUG = "net-worth-over-time";

  /** URL slug for {@link #thisMonthVsLast()}. */
  public static final String THIS_MONTH_VS_LAST_SLUG = "this-month-vs-last";

  private Presets() {}

  /**
   * FR-ANA-07: expense and income turnover, net, in base, by top-level category and month —
   * year-to-date, with both totals shown.
   */
  public static ReportSpec categoryMonthMatrix() {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("income", "expense"),
        List.of(),
        DateRange.yearToDate(),
        true,
        true,
        true);
  }

  /**
   * The balance sheet: closing balance of every top-level asset/liability/equity account, in base
   * and native, as of today. Read-only reflection of the current position — not the accounts
   * management screen (reporting.md §16).
   */
  public static ReportSpec balanceSheet() {
    RangeEndpoint today = new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START);
    return new ReportSpec(
        List.of(Dimension.ACCOUNT),
        List.of(),
        List.of(),
        List.of(
            Measure.closingBalance(PresentationCurrency.BASE),
            Measure.closingBalance(PresentationCurrency.ACCOUNT)),
        Scope.ofTypes("asset", "liability", "equity"),
        List.of(),
        new DateRange(today, today),
        false,
        true,
        true);
  }

  /**
   * Net worth over time (§16): closing balance of every asset/liability account, in base, over the
   * last 12 months — excludes equity (opening balances), income and expense. Per-person debt leaves
   * are {@code asset} accounts, so they count toward net worth (Q-REP-1, defaulted yes).
   */
  public static ReportSpec netWorthOverTime() {
    return new ReportSpec(
        List.of(),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.closingBalance(PresentationCurrency.BASE)),
        Scope.ofTypes("asset", "liability"),
        List.of(),
        DateRange.last12Months(),
        false,
        false,
        false);
  }

  /**
   * This month vs last (§16): expense and income turnover, net, in base, by top-level category —
   * the current (partial) month next to the previous (full) one, as a series so both render as one
   * grouped bar chart rather than two small multiples.
   */
  public static ReportSpec thisMonthVsLast() {
    return new ReportSpec(
        List.of(),
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("income", "expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.START),
            new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START)),
        false,
        false,
        false);
  }
}
