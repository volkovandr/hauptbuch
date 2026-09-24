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
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportGridBuilder} wired to a real {@link CellValuation} (only
 * rates mocked) — axis assembly, the credit-natural display flip (data-model §4.1), the legality
 * rules (reporting.md §7.2), row suppression (§7.3) and totals (§7.1) exercised end to end through
 * {@link ReportGridBuilder#build}. Inputs are hand-built rather than routed through {@link
 * ReportEngine}, so each test states exactly the raw data a fetch would have produced.
 */
class ReportGridBuilderTest {

  private static final RangeResolver.ResolvedRange JANUARY =
      new RangeResolver.ResolvedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

  private final ExchangeRateService exchangeRateService = mock();
  private final ReportGridBuilder builder =
      new ReportGridBuilder(new CellValuation(exchangeRateService));

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
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
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

  // ── frontierNodes (stage e nesting, §3/§9) ──────────────────────────────────

  @Test
  void frontierNodesIsOneImplicitTotalWhenTheAxisHasNoDimension() {
    assertThat(builder.frontierNodes(null, null, Map.of(), Map.of(), Map.of(), Set.of()))
        .extracting(AxisNode::label)
        .containsExactly("Total");
  }

  @Test
  void frontierNodesMarksHierarchicalOuterNodeExpandableEvenWithNoSecondDimensionNamed() {
    // A single hierarchical dimension is still expandable into its own hierarchy (reporting.md
    // §9.1) — the actual children are fetched only once the node is expanded.
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense", true));

    List<AxisNode> frontier =
        builder.frontierNodes(Dimension.CATEGORY, null, byKey, Map.of(), Map.of(), Set.of());

    assertThat(frontier).extracting(AxisNode::label).containsExactly("Food");
    assertThat(frontier.get(0).depth()).isZero();
    assertThat(frontier.get(0).expandable()).isTrue();
  }

  @Test
  void frontierNodesNeverMarksSameDimensionNodeExpandableWhenItHasNoChildrenOfItsOwn() {
    // reporting.md §9.1's own toggle only makes sense when there is something to reveal — a
    // childless top-level category (a real leaf) must never draw an expand control.
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Fuel", "expense", false));

    List<AxisNode> frontier =
        builder.frontierNodes(Dimension.CATEGORY, null, byKey, Map.of(), Map.of(), Set.of());

    assertThat(frontier.get(0).expandable()).isFalse();
  }

  @Test
  void frontierNodesMarksHierarchicalOuterNodeExpandableWhenThereIsSecondDimension() {
    Map<String, TopLevelNode> outer = candidates(new TopLevelNode("1", "Trips", null));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.TAG, Dimension.CATEGORY, outer, Map.of(), Map.of(), Set.of());

    assertThat(frontier).hasSize(1);
    assertThat(frontier.get(0).expandable()).isTrue();
    assertThat(frontier.get(0).depth()).isZero();
  }

  @Test
  void frontierNodesNeverMarksFlatOuterDimensionExpandable() {
    Map<String, TopLevelNode> outer = candidates(new TopLevelNode("1", "Some payee", null));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.PAYEE, Dimension.CATEGORY, outer, Map.of(), Map.of(), Set.of());

    assertThat(frontier.get(0).expandable()).isFalse();
  }

  @Test
  void frontierNodesNeverMarksThePersonalDebtsPseudoBucketExpandable() {
    Map<String, TopLevelNode> outer =
        candidates(new TopLevelNode("personal:EUR", "Personal debts (EUR)", "asset"));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.ACCOUNT, Dimension.CATEGORY, outer, Map.of(), Map.of(), Set.of());

    assertThat(frontier.get(0).expandable()).isFalse();
  }

  @Test
  void frontierNodesFlattensAnExpandedOuterNodesCrossDimensionChildrenRightAfterIt() {
    Map<String, TopLevelNode> outer =
        candidates(new TopLevelNode("1", "Trips", null), new TopLevelNode("2", "Car", null));
    Map<String, TopLevelNode> inner =
        candidates(
            new TopLevelNode("10", "Food", "expense"),
            new TopLevelNode("11", "Lodging", "expense"));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.TAG, Dimension.CATEGORY, outer, inner, Map.of(), Set.of("1"));

    assertThat(frontier).extracting(AxisNode::key).containsExactly("1", "1|10", "1|11", "2");
    assertThat(frontier.get(1).depth()).isEqualTo(1);
    assertThat(frontier.get(1).parentKey()).isEqualTo("1");
    assertThat(frontier.get(1).label()).isEqualTo("Food");
    assertThat(frontier.get(1).expandable()).isFalse();
  }

  @Test
  void frontierNodesMarksAnExpandedOuterNodeExpandedButLeavesUnexpandedOnesNotAndChildrenNever() {
    // Stage e2 (§9.1/§9.2): the UI needs to tell an expanded node from a collapsed one to draw the
    // right toggle icon, per node — not just uniformly for the whole axis.
    Map<String, TopLevelNode> outer =
        candidates(new TopLevelNode("1", "Trips", null), new TopLevelNode("2", "Car", null));
    Map<String, TopLevelNode> inner = candidates(new TopLevelNode("10", "Food", "expense"));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.TAG, Dimension.CATEGORY, outer, inner, Map.of(), Set.of("1"));

    assertThat(frontier).extracting(AxisNode::key).containsExactly("1", "1|10", "2");
    assertThat(frontier.get(0).expanded()).isTrue();
    assertThat(frontier.get(1).expanded()).isFalse();
    assertThat(frontier.get(2).expanded()).isFalse();
  }

  @Test
  void frontierNodesFlattensAnExpandedOuterNodesSameDimensionChildrenRightAfterIt() {
    // §9.1's same-dimension case: a plain rows = [Category] report expanding "Food" into its own
    // children, keyed by outer node rather than reused across every expanded node.
    Map<String, TopLevelNode> outer =
        candidates(
            new TopLevelNode("1", "Food", "expense", true),
            new TopLevelNode("2", "Fuel", "expense"));
    Map<String, List<TopLevelNode>> sameDimensionChildren =
        Map.of("1", List.of(new TopLevelNode("10", "Restaurants", "expense")));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.CATEGORY, null, outer, Map.of(), sameDimensionChildren, Set.of("1"));

    assertThat(frontier).extracting(AxisNode::key).containsExactly("1", "1|10", "2");
    assertThat(frontier.get(1).depth()).isEqualTo(1);
    assertThat(frontier.get(1).parentKey()).isEqualTo("1");
    assertThat(frontier.get(1).label()).isEqualTo("Restaurants");
  }

  @Test
  void frontierNodesRecursesIntoDepth1SameDimensionNodesOwnChildrenWhenBothAreExpanded() {
    // reporting.md §9.1's multilevel case (the owner's own report): a plain rows = [Category]
    // report where Food's child Restaurants is itself expanded into ITS own children — the tree
    // must keep going, not stop at one level.
    Map<String, TopLevelNode> outer = candidates(new TopLevelNode("1", "Food", "expense", true));
    TopLevelNode restaurants = new TopLevelNode("10", "Restaurants", "expense", true);
    TopLevelNode fastFood = new TopLevelNode("100", "Fast food", "expense", false);
    Map<String, List<TopLevelNode>> sameDimensionChildren =
        Map.of("1", List.of(restaurants), "1|10", List.of(fastFood));

    List<AxisNode> frontier =
        builder.frontierNodes(
            Dimension.CATEGORY, null, outer, Map.of(), sameDimensionChildren, Set.of("1", "1|10"));

    assertThat(frontier).extracting(AxisNode::key).containsExactly("1", "1|10", "1|10|100");
    AxisNode restaurantsNode = frontier.get(1);
    assertThat(restaurantsNode.depth()).isEqualTo(1);
    assertThat(restaurantsNode.expandable()).isTrue();
    assertThat(restaurantsNode.expanded()).isTrue();
    AxisNode fastFoodNode = frontier.get(2);
    assertThat(fastFoodNode.depth()).isEqualTo(2);
    assertThat(fastFoodNode.parentKey()).isEqualTo("1|10");
    assertThat(fastFoodNode.expandable()).isFalse();
  }

  @Test
  void frontierNodesLeavesAnUnexpandedOuterNodeWithNoChildrenInTheFrontier() {
    Map<String, TopLevelNode> outer = candidates(new TopLevelNode("1", "Trips", null));
    Map<String, TopLevelNode> inner = candidates(new TopLevelNode("10", "Food", "expense"));

    List<AxisNode> frontier =
        builder.frontierNodes(Dimension.TAG, Dimension.CATEGORY, outer, inner, Map.of(), Set.of());

    assertThat(frontier).extracting(AxisNode::key).containsExactly("1");
  }

  // ── dateFrontierNodes (Date's own expand-in-place tree, §9.1, stage e4b) ────────────────────

  private static List<DateBucket> monthsJanToFeb() {
    return DateBucket.bucketsBetween(
        DateGranularity.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
  }

  @Test
  void dateFrontierNodesIsOneExpandableCollapsedRowPerBucketWhenNothingIsExpanded() {
    List<AxisNode> frontier = builder.dateFrontierNodes(monthsJanToFeb(), Set.of());

    assertThat(frontier).extracting(AxisNode::key).containsExactly("2026-01", "2026-02");
    assertThat(frontier).allMatch(n -> n.depth() == 0 && n.expandable() && !n.expanded());
  }

  @Test
  void dateFrontierNodesListsAnExpandedBucketsDaysRightAfterIt() {
    List<AxisNode> frontier = builder.dateFrontierNodes(monthsJanToFeb(), Set.of("2026-01"));

    assertThat(frontier).hasSize(2 + 31);
    assertThat(frontier.get(0).expanded()).isTrue();
    AxisNode firstDay = frontier.get(1);
    assertThat(firstDay.key()).isEqualTo("2026-01|2026-01-01");
    assertThat(firstDay.label()).isEqualTo("1 Jan 2026");
    assertThat(firstDay.depth()).isEqualTo(1);
    assertThat(firstDay.parentKey()).isEqualTo("2026-01");
    assertThat(firstDay.expandable()).isFalse();
    assertThat(frontier.get(32).key()).isEqualTo("2026-02");
    assertThat(frontier.get(32).expanded()).isFalse();
  }

  @Test
  void expandedDateRowsDaysReadTheirOwnCompositeBucketKeysAndColumnTotalCountsTheMonthOnce() {
    AxisPlan axes = new AxisPlan(Dimension.DATE, null, null, true, false);
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.DATE),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 2))),
            false,
            true,
            false);
    List<DateBucket> january =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
    List<AxisNode> rows = builder.dateFrontierNodes(january, Set.of("2026-01"));
    List<AxisNode> columns = builder.axisNodes(null, Map.of(), List.of());
    GridData data =
        turnoverData(
            turnover("total", "Total", null, "2026-01", "EUR", "30.00", "30.00"),
            turnover("total", "Total", null, "2026-01|2026-01-01", "EUR", "10.00", "10.00"),
            turnover("total", "Total", null, "2026-01|2026-01-02", "EUR", "20.00", "20.00"));

    ReportGrid grid =
        builder.build(
            spec,
            axes,
            rows,
            columns,
            Map.of(),
            data,
            "EUR",
            new RangeResolver.ResolvedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));

    assertThat(grid.cells())
        .extracting(row -> row.get(0))
        .containsExactly(
            new Cell.Value(new BigDecimal("30.00"), "EUR"),
            new Cell.Value(new BigDecimal("10.00"), "EUR"),
            new Cell.Value(new BigDecimal("20.00"), "EUR"));
    assertThat(grid.columnTotals()).containsExactly(new Cell.Value(new BigDecimal("30.00"), "EUR"));
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
  void columnsWithNoDataAreKeptByDefault() {
    AxisPlan axes = new AxisPlan(null, Dimension.CATEGORY, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode(AxisNode.TOTAL_KEY, "Total"));
    List<AxisNode> columns = List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel"));
    Map<String, TopLevelNode> byKey =
        candidates(
            new TopLevelNode("1", "Food", "expense"), new TopLevelNode("2", "Fuel", "expense"));
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "50.00", "50.00"));

    ReportGrid grid =
        builder.build(
            matrixSpec(false, false, false), axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columns()).extracting(AxisNode::label).containsExactly("Food", "Fuel");
  }

  @Test
  void suppressesAnAllBlankColumnWhenEnabled() {
    AxisPlan axes = new AxisPlan(null, Dimension.CATEGORY, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode(AxisNode.TOTAL_KEY, "Total"));
    List<AxisNode> columns = List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel"));
    Map<String, TopLevelNode> byKey =
        candidates(
            new TopLevelNode("1", "Food", "expense"), new TopLevelNode("2", "Fuel", "expense"));
    // "Fuel" has no turnover at all — e.g. the same gap an unfiltered account candidate list
    // leaves for a "touching …" filter that excludes it entirely (reporting.md §6.2/§7.3).
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "50.00", "50.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            false,
            false,
            DateLadder.MONTH,
            true);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columns()).extracting(AxisNode::label).containsExactly("Food");
    assertThat(grid.cells().get(0)).containsExactly(new Cell.Value(new BigDecimal("50.00"), "EUR"));
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

    assertThat(grid.cells().get(0).get(0)).isEqualTo(new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));
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

    assertThat(grid.cells().get(0).get(0)).isEqualTo(new Cell.Illegal(Cell.Reason.MISSING_RATE));
  }

  @Test
  void totalPropagatesItsOwnIllegalAddendsReasonRatherThanGenericising() {
    // A row/column total with no structural reason of its own (single measure, no time/tag axis)
    // still can't sum an already-illegal cell — it must carry that cell's own reason forward, not a
    // fresh, less specific one.
    AxisPlan axes = new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    RawTurnoverCell missingRate =
        new RawTurnoverCell(
            "1", "Food", "expense", "2026-01", "CHF", new BigDecimal("10.00"), null, 1, 1, 1);
    GridData data = turnoverData(missingRate);
    ReportSpec spec = matrixSpecWithTotals(List.of(Dimension.CATEGORY), List.of());

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rowTotals()).containsExactly(new Cell.Illegal(Cell.Reason.MISSING_RATE));
    assertThat(grid.columnTotals()).containsExactly(new Cell.Illegal(Cell.Reason.MISSING_RATE));
    assertThat(grid.grandTotal()).isEqualTo(new Cell.Illegal(Cell.Reason.MISSING_RATE));
  }

  private static ReportSpec matrixSpecWithTotals(List<Dimension> rows, List<Dimension> columns) {
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        true,
        true,
        false);
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

    assertThat(grid.columnTotals()).containsExactly(new Cell.Illegal(Cell.Reason.CROSS_TAG_TOTAL));
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

    assertThat(grid.rowTotals()).containsExactly(new Cell.Illegal(Cell.Reason.TIME_AXIS_BALANCE));
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

  // ── stage e nesting: totals must not double-count an expanded node's children ──

  @Test
  void columnAndGrandTotalsSumOnlyTopLevelRowsNotAnExpandedNodesChildrenToo() {
    // §9.1's same-dimension case: plain rows = [Category], "Food" expanded into its own children
    // Restaurants (30) and Snacks (5). Food's own subtotal cell (35) already reflects both — a
    // column/grand total that also walks the two depth-1 child rows would double it to 70. (Neither
    // dimension here is Tag, so the pre-existing §7.2 "no total across tags" rule cannot mask this
    // — it isolates the depth-blind summation bug on its own.)
    AxisPlan axes = new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Food", 0, true, null),
            new AxisNode("1|10", "Restaurants", 1, false, "1"),
            new AxisNode("1|11", "Snacks", 1, false, "1"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "35.00", "35.00"),
            turnover("1|10", "Restaurants", "expense", "2026-01", "EUR", "30.00", "30.00"),
            turnover("1|11", "Snacks", "expense", "2026-01", "EUR", "5.00", "5.00"));
    ReportSpec spec = matrixSpecWithTotals(List.of(Dimension.CATEGORY), List.of());

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rowTotals())
        .containsExactly(
            new Cell.Value(new BigDecimal("35.00"), "EUR"),
            new Cell.Value(new BigDecimal("30.00"), "EUR"),
            new Cell.Value(new BigDecimal("5.00"), "EUR"));
    assertThat(grid.columnTotals()).containsExactly(new Cell.Value(new BigDecimal("35.00"), "EUR"));
    assertThat(grid.grandTotal()).isEqualTo(new Cell.Value(new BigDecimal("35.00"), "EUR"));
  }

  @Test
  void rowTotalSumsOnlyTopLevelColumnsNotAnExpandedNodesChildrenTooWhenNestingIsOnColumns() {
    // Mirrors columnAndGrandTotalsSumOnlyTopLevelRowsNotAnExpandedNodesChildrenToo, but with the
    // stage-e nested dimension on the COLUMN axis instead of rows — the e1 code-review finding: a
    // row total summed blindly across every column would double-count Food's own subtotal (35)
    // together with its already-included children Restaurants (30) and Snacks (5), reading 70
    // instead of 35.
    AxisPlan axes = new AxisPlan(null, Dimension.CATEGORY, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode("total", "Total"));
    List<AxisNode> columns =
        List.of(
            new AxisNode("1", "Food", 0, true, null),
            new AxisNode("1|10", "Restaurants", 1, false, "1"),
            new AxisNode("1|11", "Snacks", 1, false, "1"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "35.00", "35.00"),
            turnover("1|10", "Restaurants", "expense", "2026-01", "EUR", "30.00", "30.00"),
            turnover("1|11", "Snacks", "expense", "2026-01", "EUR", "5.00", "5.00"));
    ReportSpec spec = matrixSpecWithTotals(List.of(), List.of(Dimension.CATEGORY));

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.rowTotals()).containsExactly(new Cell.Value(new BigDecimal("35.00"), "EUR"));
  }

  @Test
  void perMeasureColumnsKeepTheirBucketsDepthAndExpandedFlag() {
    // Column headers show hierarchy (stage e5): with two measures each bucket renders as two
    // columns, and both must still say how deep the bucket sits and whether it is an expanded
    // parent, rather than every per-measure column reading as a flat depth-0 one.
    AxisPlan axes = new AxisPlan(null, Dimension.CATEGORY, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode("total", "Total"));
    List<AxisNode> columns =
        List.of(
            new AxisNode("1", "Food", 0, true, null, true),
            new AxisNode("1|10", "Restaurants", 1, false, "1"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "30.00", "30.00"),
            turnover("1|10", "Restaurants", "expense", "2026-01", "EUR", "30.00", "30.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(
                Measure.turnover(PresentationCurrency.BASE, Leg.NET),
                Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            false);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columns()).extracting(AxisNode::depth).containsExactly(0, 0, 1, 1);
    assertThat(grid.columns())
        .extracting(AxisNode::expanded)
        .containsExactly(true, true, false, false);
  }

  @Test
  void nestedChildRowFlipsSignByItsOwnMatchedTypeNotTheOuterScopeGuess() {
    // Scope spans both income and expense (a full P&L), so the scope-wide guess is ambiguous and
    // leaves a cell unflipped (§4.1's fallback) — but a nested income category under an expanded
    // Tag node must still flip by its OWN type, which the raw cell's own dimensionType carries even
    // though candidatesByKey only ever holds the outer (Tag) dimension's nodes.
    AxisPlan axes =
        new AxisPlan(Dimension.TAG, null, Dimension.TAG, Dimension.CATEGORY, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Trip", 0, true, null),
            new AxisNode("1|10", "Rebate", 1, false, "1"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Trip", null));
    // Stored as a credit (negative) — an income category must read positive regardless of the
    // ambiguous outer scope (data-model §4.1).
    GridData data =
        turnoverData(
            turnover("1", "Trip", null, "2026-01", "EUR", "-30.00", "-30.00"),
            turnover("1|10", "Rebate", "income", "2026-01", "EUR", "-30.00", "-30.00"));
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income", "expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            false);

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(1).get(0))
        .isEqualTo(new Cell.Value(new BigDecimal("30.00"), "EUR"));
  }

  @Test
  void columnTotalIsForbiddenWhenTagIsTheNestedInnerDimensionNotJustTheOuterOne() {
    // §7.2: overlapping tag rows/columns cannot legally sum. rows = [Category, Tag] nests Tag as
    // the INNER dimension — axes.rowDim() is CATEGORY, not TAG, so a check that only compares
    // rowDim/colDim against TAG would miss this and silently sum overlapping tag children.
    AxisPlan axes =
        new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, Dimension.TAG, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Fuel", 0, true, null), new AxisNode("1|10", "Trip", 1, false, "1"));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Fuel", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Fuel", "expense", "2026-01", "EUR", "50.00", "50.00"),
            turnover("1|10", "Trip", null, "2026-01", "EUR", "20.00", "20.00"));
    ReportSpec spec = matrixSpecWithTotals(List.of(Dimension.CATEGORY, Dimension.TAG), List.of());

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.columnTotals()).containsExactly(new Cell.Illegal(Cell.Reason.CROSS_TAG_TOTAL));
  }

  // ── stage e3: a parent row as a subtotal vs. a bare group header ──────────

  private static ReportSpec matrixSpecWithGroupHeaderParents(List<Dimension> rows) {
    return new ReportSpec(
        rows,
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        true,
        true,
        false,
        true);
  }

  @Test
  void groupHeaderParentsBlanksAnExpandedParentRowsOwnCellsAndRowTotalButNotItsChildren() {
    // reporting.md §9.2: an expanded parent is either a subtotal or a bare group header — off by
    // default (see the sibling test below), but with the toggle on, Food's own aggregate should
    // disappear since Restaurants/Snacks right beneath it already show the breakdown.
    AxisPlan axes = new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Food", 0, true, null, true),
            new AxisNode("1|10", "Restaurants", 1, false, "1", false),
            new AxisNode("1|11", "Snacks", 1, false, "1", false));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "35.00", "35.00"),
            turnover("1|10", "Restaurants", "expense", "2026-01", "EUR", "30.00", "30.00"),
            turnover("1|11", "Snacks", "expense", "2026-01", "EUR", "5.00", "5.00"));
    ReportSpec spec = matrixSpecWithGroupHeaderParents(List.of(Dimension.CATEGORY));

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0)).containsExactly(Cell.BLANK);
    assertThat(grid.cells().get(1)).containsExactly(new Cell.Value(new BigDecimal("30.00"), "EUR"));
    assertThat(grid.cells().get(2)).containsExactly(new Cell.Value(new BigDecimal("5.00"), "EUR"));
    assertThat(grid.rowTotals())
        .containsExactly(
            Cell.BLANK,
            new Cell.Value(new BigDecimal("30.00"), "EUR"),
            new Cell.Value(new BigDecimal("5.00"), "EUR"));
    // The real subtotal still backs the column/grand total — a header-only choice is display-only.
    assertThat(grid.columnTotals()).containsExactly(new Cell.Value(new BigDecimal("35.00"), "EUR"));
    assertThat(grid.grandTotal()).isEqualTo(new Cell.Value(new BigDecimal("35.00"), "EUR"));
  }

  @Test
  void groupHeaderParentsLeavesCollapsedParentRowsAggregateAloneSinceNothingElseShowsIt() {
    // A collapsed node is the only row standing in for its whole subtree — blanking it would lose
    // the figure entirely, not just move it, so the toggle must not touch a collapsed parent.
    AxisPlan axes = new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, false, false);
    List<AxisNode> rows = List.of(new AxisNode("1", "Food", 0, true, null, false));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(turnover("1", "Food", "expense", "2026-01", "EUR", "35.00", "35.00"));
    ReportSpec spec = matrixSpecWithGroupHeaderParents(List.of(Dimension.CATEGORY));

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0)).containsExactly(new Cell.Value(new BigDecimal("35.00"), "EUR"));
  }

  @Test
  void groupHeaderParentsOffKeepsTheExpandedParentsSubtotalTheDefaultBehavior() {
    AxisPlan axes = new AxisPlan(Dimension.CATEGORY, null, Dimension.CATEGORY, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Food", 0, true, null, true),
            new AxisNode("1|10", "Restaurants", 1, false, "1", false));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Food", "expense"));
    GridData data =
        turnoverData(
            turnover("1", "Food", "expense", "2026-01", "EUR", "35.00", "35.00"),
            turnover("1|10", "Restaurants", "expense", "2026-01", "EUR", "30.00", "30.00"));
    ReportSpec spec = matrixSpecWithTotals(List.of(Dimension.CATEGORY), List.of());

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0)).containsExactly(new Cell.Value(new BigDecimal("35.00"), "EUR"));
  }

  @Test
  void groupHeaderParentsRendersCrossDimensionParentAsBareHeadingPerTheDocsWorkedExample() {
    // reporting.md §9.2's own example: Tag is one of {Trips}, rows = [Tag, Category], auto
    // expansion and group-header parents — Trips renders as a bare heading, one row per trip
    // (here, per category) beneath it.
    AxisPlan axes =
        new AxisPlan(Dimension.TAG, null, Dimension.TAG, Dimension.CATEGORY, false, false);
    List<AxisNode> rows =
        List.of(
            new AxisNode("1", "Trips", 0, true, null, true),
            new AxisNode("1|10", "Food", 1, false, "1", false),
            new AxisNode("1|11", "Lodging", 1, false, "1", false));
    List<AxisNode> columns = List.of(new AxisNode("total", "Total"));
    Map<String, TopLevelNode> byKey = candidates(new TopLevelNode("1", "Trips", null));
    GridData data =
        turnoverData(
            turnover("1", "Trips", null, "2026-01", "EUR", "300.00", "300.00"),
            turnover("1|10", "Food", "expense", "2026-01", "EUR", "120.00", "120.00"),
            turnover("1|11", "Lodging", "expense", "2026-01", "EUR", "180.00", "180.00"));
    ReportSpec spec = matrixSpecWithGroupHeaderParents(List.of(Dimension.TAG, Dimension.CATEGORY));

    ReportGrid grid = builder.build(spec, axes, rows, columns, byKey, data, "EUR", JANUARY);

    assertThat(grid.cells().get(0)).containsExactly(Cell.BLANK);
    assertThat(grid.cells().get(1))
        .containsExactly(new Cell.Value(new BigDecimal("120.00"), "EUR"));
    assertThat(grid.cells().get(2))
        .containsExactly(new Cell.Value(new BigDecimal("180.00"), "EUR"));
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

    assertThat(grid.cells().get(0).get(0)).isEqualTo(new Cell.Illegal(Cell.Reason.MISSING_RATE));
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
    assertThat(grid.rowTotals()).containsExactly(new Cell.Illegal(Cell.Reason.MULTI_MEASURE_TOTAL));
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

    assertThat(grid.refusalMessage())
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

    assertThat(grid.refusalMessage()).isNull();
  }
}
