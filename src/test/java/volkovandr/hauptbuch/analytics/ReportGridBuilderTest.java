package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportGridBuilder} — axis assembly, the credit-natural display
 * flip (data-model §4.1), the legality rules (reporting.md §7.2), row suppression (§7.3) and totals
 * (§7.1) — with only rates mocked. Inputs are hand-built rather than routed through {@link
 * ReportEngine}, so each test states exactly the raw data a fetch would have produced.
 */
class ReportGridBuilderTest {

  private static final RangeResolver.ResolvedRange JANUARY =
      new RangeResolver.ResolvedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

  private final ExchangeRateService exchangeRateService = mock();
  private final ReportGridBuilder builder = new ReportGridBuilder(exchangeRateService);

  private static ReportSpec matrixSpec(
      boolean rowTotals, boolean columnTotals, boolean suppressEmptyRows) {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        rowTotals,
        columnTotals,
        suppressEmptyRows);
  }

  private static RawTurnoverCell turnover(
      String key,
      String label,
      String type,
      String month,
      String currency,
      String nativeAmount,
      String base) {
    return new RawTurnoverCell(
        key,
        label,
        type,
        month,
        currency,
        new BigDecimal(nativeAmount),
        new BigDecimal(base),
        0,
        1,
        1);
  }

  private static RawTurnoverCell turnoverWithCounts(
      String key,
      String label,
      String type,
      String month,
      String currency,
      String nativeAmount,
      long postingCount,
      long transactionCount) {
    return new RawTurnoverCell(
        key,
        label,
        type,
        month,
        currency,
        new BigDecimal(nativeAmount),
        new BigDecimal(nativeAmount),
        0,
        postingCount,
        transactionCount);
  }

  private static ReportSpec countSpec(Measure measure, boolean rowTotals) {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(),
        List.of(measure),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        rowTotals,
        false,
        false);
  }

  private static Map<String, TopLevelNode> candidates(TopLevelNode... nodes) {
    Map<String, TopLevelNode> byKey = new LinkedHashMap<>();
    for (TopLevelNode node : nodes) {
      byKey.put(node.key(), node);
    }
    return byKey;
  }

  private static GridData turnoverData(RawTurnoverCell... cells) {
    return new GridData(Map.of(Leg.NET, List.of(cells)), Map.of(), Map.of());
  }

  // ── axisNodes ─────────────────────────────────────────────────────────────

  @Test
  void axisNodesIsOneImplicitTotalWhenTheAxisHasNoDimension() {
    assertThat(builder.axisNodes(null, Map.of(), List.of()))
        .extracting(AxisNode::label)
        .containsExactly("Total");
  }

  @Test
  void axisNodesIsMonthBucketsForDate() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    assertThat(builder.axisNodes(Dimension.DATE, Map.of(), buckets))
        .extracting(AxisNode::label)
        .containsExactly("Jan 2026");
  }

  @Test
  void axisNodesIsTheCandidateListForWiredDimension() {
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    assertThat(builder.axisNodes(Dimension.CATEGORY, byKey, List.of()))
        .extracting(AxisNode::label)
        .containsExactly("Food");
  }

  // ── category×month matrix shape ──────────────────────────────────────────

  @Test
  void rendersOneColumnPerMonthBucketAndOneRowPerTopLevelCategory() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey =
        candidates(
            new TopLevelNode("1", "Food", "expense"), new TopLevelNode("2", "Fuel", "expense"));
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "50.00", "50.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, false), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rows()).extracting(AxisNode::label).containsExactly("Food", "Fuel");
    assertThat(grid.columns()).extracting(AxisNode::label).containsExactly("Jan 2026");
    assertThat(grid.cells().get(0).get(0))
        .isEqualTo(new Cell.Value(new BigDecimal("50.00"), "EUR"));
    assertThat(grid.cells().get(1).get(0)).isEqualTo(Cell.BLANK);
  }

  @Test
  void suppressesAnAllBlankRowByDefault() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey =
        candidates(
            new TopLevelNode("1", "Food", "expense"), new TopLevelNode("2", "Fuel", "expense"));
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "50.00", "50.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, true), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rows()).extracting(AxisNode::label).containsExactly("Food");
  }

  @Test
  void negatesCreditNaturalRowForDisplay() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Salary"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Salary", "income"));
    // Stored as a credit (negative); the income block must read positive (data-model §4.1).
    GridData data =
        turnoverData(turnover("1", "Salary", "income", "2026-01", "EUR", "-3000.00", "-3000.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, false), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0))
        .isEqualTo(new Cell.Value(new BigDecimal("3000.00"), "EUR"));
  }

  @Test
  void anAccountCurrencyCellSpanningTwoCurrenciesIsIllegal() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "20.00", "20.00"),
            turnover("1", "Food", "expense", "2026-01", "CHF", "10.00", "9.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0)).isEqualTo(Cell.ILLEGAL);
  }

  @Test
  void missingRateOnTurnoverPostingMakesTheBaseCellIllegalNotSilentlyPartial() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    RawTurnoverCell missingRate =
        new RawTurnoverCell(
            "1", "Food", "expense", "2026-01", "CHF", new BigDecimal("10.00"), null, 1, 1, 1);
    GridData data = turnoverData(missingRate);

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, false), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0)).isEqualTo(Cell.ILLEGAL);
  }

  @Test
  void grandTotalAcrossTagRowsIsIllegal() {
    AxisPlan axes = new AxisPlan(Dimension.TAG, null, Dimension.TAG, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Car"), new AxisNode("2", "Trip"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey =
        candidates(new TopLevelNode("1", "Car", null), new TopLevelNode("2", "Trip", null));
    GridData data =
        turnoverData(
            turnover("1", "Car", null, "2026-01", "EUR", "40.00", "40.00"),
            turnover("2", "Trip", null, "2026-01", "EUR", "40.00", "40.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.TAG),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            true,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columnTotals()).containsExactly(Cell.ILLEGAL);
  }

  @Test
  void totalSummingClosingBalanceAcrossMonthsIsIllegal() {
    // rows=Category (accounts), columns=Date: a row total sums ACROSS the Date columns — across
    // time — which is the illegal direction (§7.2). Summing DOWN rows (across accounts) is legal.
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Cash"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Cash", "asset"));
    GridData data =
        new GridData(
            Map.of(),
            Map.of(
                "2026-01",
                List.of(new RawBalanceCell("1", "Cash", "asset", "EUR", new BigDecimal("100.00")))),
            Map.of("2026-01", LocalDate.of(2026, 1, 31)));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.closingBalance(PresentationCurrency.BASE)),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 2, 28))),
            true,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rowTotals()).containsExactly(Cell.ILLEGAL);
  }

  @Test
  void computesRowAndColumnTotalsAndTheGrandTotal() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey =
        candidates(
            new TopLevelNode("1", "Food", "expense"), new TopLevelNode("2", "Fuel", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "20.00", "20.00"),
            turnover("2", "Fuel", "expense", "2026-01", "EUR", "10.00", "10.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(true, true, true), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rowTotals())
        .containsExactly(
            new Cell.Value(new BigDecimal("20.00"), "EUR"),
            new Cell.Value(new BigDecimal("10.00"), "EUR"));
    assertThat(grid.columnTotals()).containsExactly(new Cell.Value(new BigDecimal("30.00"), "EUR"));
    assertThat(grid.grandTotal()).isEqualTo(new Cell.Value(new BigDecimal("30.00"), "EUR"));
  }

  // ── balance sheet shape (rows=Account, no column dimension, two measures) ──

  @Test
  void balanceSheetHasOneColumnPerMeasureWithNoColumnDimension() {
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Cash"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Cash", "asset"));
    GridData data =
        new GridData(
            Map.of(),
            Map.of(
                "total",
                List.of(
                    new RawBalanceCell("1", "Cash", "asset", "EUR", new BigDecimal("1000.00")))),
            Map.of("total", LocalDate.of(2026, 1, 31)));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(),
            List.of(
                Measure.closingBalance(PresentationCurrency.BASE),
                Measure.closingBalance(PresentationCurrency.ACCOUNT)),
            Scope.ofTypes("asset", "liability", "equity"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columns())
        .extracting(AxisNode::label)
        .containsExactly("Closing balance", "Closing balance (native)");
    assertThat(grid.cells().get(0))
        .containsExactly(
            new Cell.Value(new BigDecimal("1000.00"), "EUR"),
            new Cell.Value(new BigDecimal("1000.00"), "EUR"));
  }

  @Test
  void closingBalanceInBaseMultipliesTheNativeSumByTheRateAsOfTheAsOfDate() {
    when(exchangeRateService.rateAsOf("CHF", LocalDate.of(2026, 1, 31)))
        .thenReturn(Optional.of(new BigDecimal("0.90")));
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Swiss Cash"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Swiss Cash", "asset"));
    GridData data =
        new GridData(
            Map.of(),
            Map.of(
                "total",
                List.of(
                    new RawBalanceCell(
                        "1", "Swiss Cash", "asset", "CHF", new BigDecimal("100.00")))),
            Map.of("total", LocalDate.of(2026, 1, 31)));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(),
            List.of(Measure.closingBalance(PresentationCurrency.BASE)),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    Cell cell = grid.cells().get(0).get(0);
    assertThat(cell).isInstanceOf(Cell.Value.class);
    assertThat(((Cell.Value) cell).amount()).isEqualByComparingTo(new BigDecimal("90.00"));
  }

  @Test
  void missingExchangeRateMakesTheClosingBalanceBaseCellIllegal() {
    when(exchangeRateService.rateAsOf("CHF", LocalDate.of(2026, 1, 31)))
        .thenReturn(Optional.empty());
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Swiss Cash"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Swiss Cash", "asset"));
    GridData data =
        new GridData(
            Map.of(),
            Map.of(
                "total",
                List.of(
                    new RawBalanceCell(
                        "1", "Swiss Cash", "asset", "CHF", new BigDecimal("100.00")))),
            Map.of("total", LocalDate.of(2026, 1, 31)));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(),
            List.of(Measure.closingBalance(PresentationCurrency.BASE)),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0)).isEqualTo(Cell.ILLEGAL);
  }

  // ── no-dimension totals (rows=[], columns=[Date]) ──────────────────────────

  @Test
  void scopeOfOnlyCreditNaturalTypesIsFlippedEvenWithNoRowOrColumnDimension() {
    AxisPlan axes = new AxisPlan(null, Dimension.DATE, null, false, true);
    List<AxisNode> rows = List.of(new AxisNode("total", "Total"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    // Stored as a credit (negative); an income-only total must still read positive (§4.1).
    GridData data =
        turnoverData(turnover("total", "Total", null, "2026-01", "EUR", "-3000.00", "-3000.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, Map.of(), data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0))
        .isEqualTo(new Cell.Value(new BigDecimal("3000.00"), "EUR"));
  }

  @Test
  void scopeMixingCreditAndDebitNaturalTypesIsNotFlippedWithNoRowOrColumnDimension() {
    AxisPlan axes = new AxisPlan(null, Dimension.DATE, null, false, true);
    List<AxisNode> rows = List.of(new AxisNode("total", "Total"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    // 200 income (stored -200) net 100 expense (stored +100) = -100 raw; ambiguous sign, left
    // as-is.
    GridData data =
        turnoverData(turnover("total", "Total", null, "2026-01", "EUR", "-100.00", "-100.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income", "expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, Map.of(), data, "EUR", JANUARY);

    assertThat(grid.cells().get(0).get(0))
        .isEqualTo(new Cell.Value(new BigDecimal("-100.00"), "EUR"));
  }

  // ── row totals across heterogeneous measures ───────────────────────────────

  @Test
  void rowTotalAcrossTwoDifferentMeasuresIsIllegalNotDoubleCounted() {
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Cash"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Cash", "asset"));
    GridData data =
        new GridData(
            Map.of(),
            Map.of(
                "total",
                List.of(
                    new RawBalanceCell("1", "Cash", "asset", "EUR", new BigDecimal("1000.00")))),
            Map.of("total", LocalDate.of(2026, 1, 31)));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(),
            List.of(
                Measure.closingBalance(PresentationCurrency.BASE),
                Measure.closingBalance(PresentationCurrency.ACCOUNT)),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            true,
            false,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    // 1000.00 (base) + 1000.00 (native) must NOT silently sum to 2000.00 — they are two views of
    // the same figure, not additive quantities.
    assertThat(grid.rowTotals()).containsExactly(Cell.ILLEGAL);
  }

  // ── count measures (§5.5) ───────────────────────────────────────────────

  @Test
  void countCellIsBlankWhenNoPostingsMatch() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data = new GridData(Map.of(Leg.NET, List.of()), Map.of(), Map.of());

    ReportGrid grid =
        builder.build(
            countSpec(Measure.countPostings(), false),
            axes,
            rows,
            columns,
            byKey,
            data,
            "EUR",
            JANUARY);

    assertThat(grid.cells().get(0).get(0)).isEqualTo(Cell.BLANK);
  }

  @Test
  void countPostingsSumsThePostingCountAcrossCurrencyGroups() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        new GridData(
            Map.of(
                Leg.NET,
                List.of(
                    turnoverWithCounts("1", "Food", "expense", "2026-01", "EUR", "20.00", 3, 3),
                    turnoverWithCounts("1", "Food", "expense", "2026-01", "CHF", "10.00", 5, 4))),
            Map.of(),
            Map.of());

    ReportGrid grid =
        builder.build(
            countSpec(Measure.countPostings(), false),
            axes,
            rows,
            columns,
            byKey,
            data,
            "EUR",
            JANUARY);

    // Every posting belongs to exactly one currency group, so summing across groups is exact.
    assertThat(grid.cells().get(0).get(0)).isEqualTo(new Cell.Count(8));
  }

  @Test
  void countTransactionsSumsTheTransactionCountAcrossCurrencyGroups() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        new GridData(
            Map.of(
                Leg.NET,
                List.of(
                    turnoverWithCounts("1", "Food", "expense", "2026-01", "EUR", "20.00", 3, 3),
                    turnoverWithCounts("1", "Food", "expense", "2026-01", "CHF", "10.00", 5, 4))),
            Map.of(),
            Map.of());

    ReportGrid grid =
        builder.build(
            countSpec(Measure.countTransactions(), false),
            axes,
            rows,
            columns,
            byKey,
            data,
            "EUR",
            JANUARY);

    assertThat(grid.cells().get(0).get(0)).isEqualTo(new Cell.Count(7));
  }

  @Test
  void rowTotalOfCountMeasureSumsTheCountsAcrossColumns() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns =
        List.of(new AxisNode("2026-01", "Jan 2026"), new AxisNode("2026-02", "Feb 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        new GridData(
            Map.of(
                Leg.NET,
                List.of(
                    turnoverWithCounts("1", "Food", "expense", "2026-01", "EUR", "20.00", 3, 3),
                    turnoverWithCounts("1", "Food", "expense", "2026-02", "EUR", "10.00", 2, 2))),
            Map.of(),
            Map.of());

    ReportGrid grid =
        builder.build(
            countSpec(Measure.countPostings(), true),
            axes,
            rows,
            columns,
            byKey,
            data,
            "EUR",
            JANUARY);

    assertThat(grid.rowTotals()).containsExactly(new Cell.Count(5));
  }

  @Test
  void carriesTheScopeMismatchMessageWhenScopeMissesTheDimension() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of();
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("asset", "liability"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportGrid grid =
        builder.build(spec, axes, rows, columns, Map.of(), turnoverData(), "EUR", JANUARY);

    assertThat(grid.scopeMismatch())
        .isEqualTo("Category covers income and expense accounts; neither is in scope.");
  }

  @Test
  void scopeMismatchIsNullWhenScopeAndDimensionAgree() {
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, Dimension.DATE, Dimension.CATEGORY, false, true);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("2026-01", "Jan 2026"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "50.00", "50.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, false), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.scopeMismatch()).isNull();
  }
}
