package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportCsv} — a rendered grid as CSV (reporting.md §13): ISO
 * dates, plain decimal points, one column per measure, the totals the grid carries, and nothing
 * else. Which rows and columns a grid has is the engine's job.
 */
class ReportCsvTest {

  private static final Measure BASE_NET = Measure.turnover(PresentationCurrency.BASE, Leg.NET);
  private static final Measure NATIVE_NET = Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET);
  private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
  private static final LocalDate FEB_28 = LocalDate.of(2026, 2, 28);

  private static ReportSpec spec(
      List<Dimension> rows, List<Dimension> columns, List<Measure> measures) {
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        measures,
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(new RangeEndpoint.Literal(JAN_1), new RangeEndpoint.Literal(FEB_28)),
        true,
        true,
        true);
  }

  private static ReportGrid grid(
      List<AxisNode> rows,
      List<AxisNode> columns,
      List<List<Cell>> cells,
      List<Cell> rowTotals,
      List<Cell> columnTotals,
      Cell grandTotal) {
    return new ReportGrid(
        rows, columns, cells, rowTotals, columnTotals, grandTotal, JAN_1, FEB_28, null);
  }

  private static Cell eur(String amount) {
    return new Cell.Value(new BigDecimal(amount), "EUR");
  }

  @Test
  void writesIsoDateColumnsPlainDecimalsAndTheTotalsTheGridCarries() {
    ReportSpec spec = spec(List.of(Dimension.CATEGORY), List.of(Dimension.DATE), List.of(BASE_NET));
    ReportGrid grid =
        grid(
            List.of(
                new AxisNode("5", "Food", 0, true, null, true),
                new AxisNode("5|6", "Restaurants", 1, false, "5")),
            List.of(new AxisNode("2026-01", "Jan 2026"), new AxisNode("2026-02", "Feb 2026")),
            List.of(List.of(eur("1234.5"), eur("-20.0000")), List.of(eur("1234.5"), Cell.BLANK)),
            List.of(eur("1214.50"), eur("1234.50")),
            List.of(eur("1234.50"), new Cell.Illegal(Cell.Reason.MISSING_RATE)),
            eur("1214.50"));

    assertThat(ReportCsv.write(spec, grid))
        .isEqualTo(
            "Category,2026-01,2026-02,Total\r\n"
                + "Food,1234.50,-20.00,1214.50\r\n"
                + "  Restaurants,1234.50,,1234.50\r\n"
                + "Total,1234.50,—,1214.50\r\n");
  }

  @Test
  void namesEachMeasureOfDateColumnsAndLeavesNativeFiguresInTheirOwnCurrencysUnits() {
    ReportSpec spec =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(NATIVE_NET, Measure.countTransactions()));
    String nativeKey = ReportGridBuilder.measureKey(NATIVE_NET);
    String countKey = ReportGridBuilder.measureKey(Measure.countTransactions());
    ReportGrid grid =
        grid(
            List.of(new AxisNode("7", "Travel")),
            List.of(
                new AxisNode("2026-01|" + nativeKey, "Jan 2026 — Turnover (native)"),
                new AxisNode("2026-01|" + countKey, "Jan 2026 — Count of transactions")),
            List.of(List.of(new Cell.Value(new BigDecimal("1500"), "JPY"), new Cell.Count(3))),
            List.of(),
            List.of(),
            Cell.BLANK);

    assertThat(ReportCsv.write(spec, grid))
        .isEqualTo(
            "Category,2026-01 — Turnover (native),2026-01 — Count of transactions\r\n"
                + "Travel,1500,3\r\n");
  }

  @Test
  void dateRowsUseTheirIsoKeyEvenForAnExpandedDayAndQuoteWhatCsvMust() {
    ReportSpec spec = spec(List.of(Dimension.DATE), List.of(Dimension.PAYEE), List.of(BASE_NET));
    ReportGrid grid =
        grid(
            List.of(
                new AxisNode("2026-01", "Jan 2026", 0, true, null, true),
                new AxisNode("2026-01|2026-01-05", "5 Jan", 1, false, "2026-01")),
            List.of(new AxisNode("3", "Shop \"Aaa\", Main St")),
            List.of(List.of(eur("5")), List.of(eur("5"))),
            List.of(),
            List.of(),
            Cell.BLANK);

    assertThat(ReportCsv.write(spec, grid))
        .isEqualTo("Date,\"Shop \"\"Aaa\"\", Main St\"\r\n2026-01,5.00\r\n2026-01-05,5.00\r\n");
  }

  @Test
  void withNoColumnDimensionTheColumnsAreTheMeasures() {
    ReportSpec spec = spec(List.of(Dimension.PAYEE), List.of(), List.of(BASE_NET));
    ReportGrid grid =
        grid(
            List.of(new AxisNode("none", "(No payee)")),
            List.of(new AxisNode(ReportGridBuilder.measureKey(BASE_NET), "Turnover")),
            List.of(List.of(eur("1"))),
            List.of(),
            List.of(),
            Cell.BLANK);

    assertThat(ReportCsv.write(spec, grid)).isEqualTo("Payee,Turnover\r\n(No payee),1.00\r\n");
  }

  @Test
  void refusedGridIsItsReasonAlone() {
    ReportSpec spec = spec(List.of(Dimension.TAG), List.of(), List.of(BASE_NET));

    assertThat(ReportCsv.write(spec, ReportGrid.refused("Nothing to show.", JAN_1, FEB_28)))
        .isEqualTo("Nothing to show.\r\n");
  }
}
