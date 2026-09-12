package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * The two Presets stage a ships (reporting.md §16): code-defined, always present, non-deletable.
 * The other two (net worth over time, this month vs last) need a chart renderer and ship in stage
 * b.
 */
public final class Presets {

  /** URL slug for {@link #categoryMonthMatrix()} (reporting.md §14 — {@code /reports/preset/*}). */
  public static final String CATEGORY_MONTH_MATRIX_SLUG = "category-month-matrix";

  /** URL slug for {@link #balanceSheet()}. */
  public static final String BALANCE_SHEET_SLUG = "balance-sheet";

  private Presets() {}

  /**
   * FR-ANA-07: expense and income turnover, net, in base, by top-level category and month —
   * year-to-date, with both totals shown.
   */
  public static ReportSpec categoryMonthMatrix() {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
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
}
