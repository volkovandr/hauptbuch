package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportEngine}'s own orchestration and validation — the
 * unsupported-feature guards, axis planning, and that it wires {@link AxisCandidates}, {@link
 * ReportDataFetcher} and {@link ReportGridBuilder} together — all mocked. Which repository query a
 * dimension maps to is {@link AxisCandidatesTest}'s and {@link ReportDataFetcherTest}'s job; grid
 * assembly is {@link ReportGridBuilderTest}'s.
 */
class ReportEngineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

  private final SettingsService settingsService = mock();
  private final AxisCandidates axisCandidates = mock();
  private final ReportDataFetcher dataFetcher = mock();
  private final ReportGridBuilder gridBuilder = mock();
  private final ReportEngine engine =
      new ReportEngine(settingsService, axisCandidates, dataFetcher, gridBuilder);

  /** A fetch no test cares about returns no data, never {@code null}. */
  @BeforeEach
  void fetchReturnsNoDataByDefault() {
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
  }

  private void baseIsEur() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));
  }

  private static ReportSpec spec(
      List<Dimension> rows, List<Dimension> columns, Measure measure, Scope scope) {
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        List.of(measure),
        scope,
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  // ── validation ────────────────────────────────────────────────────────────

  @Test
  void requiresBaseCurrencyToBeSet() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    assertThatThrownBy(() -> engine.render(s, TODAY)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void acceptsFilters() {
    baseIsEur();
    ReportSpec s =
        new ReportSpec(
            List.of(),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    assertThatCode(() -> engine.render(s, TODAY)).doesNotThrowAnyException();
  }

  @Test
  void acceptsCountMeasures() {
    baseIsEur();
    ReportSpec s = spec(List.of(), List.of(), Measure.countPostings(), Scope.ofTypes("expense"));

    assertThatCode(() -> engine.render(s, TODAY)).doesNotThrowAnyException();
  }

  @Test
  void acceptsEveryFlatDimensionInTheCatalogue() {
    baseIsEur();
    for (Dimension dim :
        List.of(Dimension.PAYEE, Dimension.PERSON, Dimension.CURRENCY, Dimension.ACCOUNT_TYPE)) {
      ReportSpec s =
          spec(
              List.of(dim),
              List.of(),
              Measure.turnover(PresentationCurrency.BASE, Leg.NET),
              Scope.ofTypes("expense"));

      assertThatCode(() -> engine.render(s, TODAY)).doesNotThrowAnyException();
    }
  }

  @Test
  void refusesClosingBalanceOnPayeeDimension() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.PAYEE),
            List.of(),
            Measure.closingBalance(PresentationCurrency.BASE),
            Scope.ofTypes("expense"));

    assertRefused(s, "A payee has no closing balance");
  }

  @Test
  void refusesTwoDifferentNonDateDimensionsOnRowsAndColumns() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.TAG),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    assertRefused(s, "two different dimensions");
  }

  @Test
  void refusesDateOnBothRowsAndColumns() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.DATE),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    assertRefused(s, "Date cannot be on both");
  }

  @Test
  void refusesClosingBalanceOnTagDimension() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.TAG),
            List.of(),
            Measure.closingBalance(PresentationCurrency.BASE),
            Scope.ofTypes("asset"));

    assertRefused(s, "A tag has no closing balance");
  }

  @Test
  void refusesClosingBalanceOnNestedTagSlot() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY, Dimension.TAG),
            List.of(),
            Measure.closingBalance(PresentationCurrency.BASE),
            Scope.ofTypes("expense"));

    assertRefused(s, "A tag has no closing balance");
  }

  /**
   * A refused spec renders as an empty grid carrying the reason (issue 18) — never an exception,
   * and never a single query: the refusal is decided from the spec alone.
   */
  private void assertRefused(ReportSpec s, String reasonFragment) {
    ReportGrid grid = engine.render(s, TODAY);

    assertThat(grid.refusalMessage()).contains(reasonFragment);
    assertThat(grid.rows()).isEmpty();
    assertThat(grid.columns()).isEmpty();
    assertThat(grid.resolvedStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(grid.resolvedEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
    verify(axisCandidates, never()).candidatesFor(any(), any());
  }

  // ── orchestration ─────────────────────────────────────────────────────────

  @Test
  void seriesFillsTheRowSlotWhenRowsIsEmpty() {
    baseIsEur();
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(),
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY);

    // The series dimension (Date) resolves candidates/data exactly as a rows dimension would —
    // ReportEngine's axis planning treats the two identically (ReportSpec's own javadoc).
    verify(axisCandidates).candidatesFor(Dimension.CATEGORY, s);
  }

  @Test
  void resolvesAxesFetchesDataAndDelegatesToTheGridBuilder() {
    baseIsEur();
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY);

    verify(axisCandidates).candidatesFor(Dimension.CATEGORY, s);
    verify(gridBuilder).build(eq(s), any(), any(), any(), any(), any(), eq("EUR"), any());
  }

  @Test
  void weekLadderChoiceProducesWeekGranularityBuckets() {
    baseIsEur();
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true,
            false,
            DateLadder.WEEK);

    engine.render(s, TODAY);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DateBucket>> buckets = ArgumentCaptor.forClass(List.class);
    verify(gridBuilder).axisNodes(eq(Dimension.DATE), any(), buckets.capture());
    assertThat(buckets.getValue())
        .isNotEmpty()
        .allSatisfy(b -> assertThat(b.granularity()).isEqualTo(DateGranularity.WEEK));
  }

  // ── stage e nesting (reporting.md §3, §9) ────────────────────────────────

  @Test
  void expandedFetchesSameDimensionChildCandidatesForEveryTopLevelNode() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY, RowExpansion.EXPANDED);

    verify(axisCandidates).childCandidatesFor(Dimension.CATEGORY, "1", s);
  }

  @Test
  void collapsedNeverFetchesEitherChildSourceEvenWhenTheAxisNestsTwoDimensions() {
    baseIsEur();
    TopLevelNode trip = new TopLevelNode("1", "Trip", null);
    when(axisCandidates.candidatesFor(eq(Dimension.TAG), any())).thenReturn(Map.of("1", trip));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY, RowExpansion.COLLAPSED);

    verify(axisCandidates, never()).candidatesFor(eq(Dimension.CATEGORY), any());
    verify(axisCandidates, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void expandedFetchesTheInnerDimensionsCandidatesForCrossDimensionNesting() {
    baseIsEur();
    TopLevelNode trip = new TopLevelNode("1", "Trip", null);
    when(axisCandidates.candidatesFor(eq(Dimension.TAG), any())).thenReturn(Map.of("1", trip));
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY, RowExpansion.EXPANDED);

    verify(axisCandidates).candidatesFor(Dimension.CATEGORY, s);
    verify(axisCandidates, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void autoExpandsOnlyTheFilteredTagNotEveryTopLevelCandidate() {
    // reporting.md §9.2's own worked example (`Tag is one of {Trips}`) names exactly one node —
    // Car, a second live top-level tag with no relation to the filter, must stay collapsed. An
    // earlier implementation wrongly expanded every top-level candidate whenever auto's one-node
    // rule fired at all (the owner's "tags always appear fully expanded" report).
    baseIsEur();
    TopLevelNode trip = new TopLevelNode("1", "Trip", null, true);
    TopLevelNode car = new TopLevelNode("2", "Car", null, true);
    when(axisCandidates.candidatesFor(eq(Dimension.TAG), any()))
        .thenReturn(Map.of("1", trip, "2", car));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.TAG),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.TAG,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY);

    verify(axisCandidates).childCandidatesFor(Dimension.TAG, "1", s);
    verify(axisCandidates, never()).childCandidatesFor(eq(Dimension.TAG), eq("2"), any());
  }

  // ── stage e2: a saved Report's remembered per-node expansion (reporting.md §9.1) ────────────

  @Test
  void explicitExpandedKeysFetchesChildrenOnlyForTheKeysNamed() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    TopLevelNode fuel = new TopLevelNode("2", "Fuel", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any()))
        .thenReturn(Map.of("1", food, "2", fuel));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY, Set.of("1"));

    verify(axisCandidates).childCandidatesFor(Dimension.CATEGORY, "1", s);
    verify(axisCandidates, never()).childCandidatesFor(eq(Dimension.CATEGORY), eq("2"), any());
  }

  @Test
  void explicitlyEmptyExpandedKeysFetchesNoChildrenEvenWhenAutoWouldHaveExpanded() {
    baseIsEur();
    // A filter selecting exactly one node is auto's own "start expanded" trigger (§9.2) — an
    // explicit (if empty) override must still win over it, since the owner hand-collapsed it.
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY, Set.of());

    verify(axisCandidates, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void explicitExpandedKeysPassesThroughDepth1CompositeKeyUnvalidated() {
    // reporting.md §9.1's same-dimension nesting recurses to arbitrary depth: a saved Report's
    // remembered set can name a nested node ("<topLevelKey>|<childKey>") that never appears in the
    // top-level candidates map this method validates depth-0 keys against — it must still reach
    // ReportDataFetcher rather than being silently dropped as though it were a stale top-level key.
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY, Set.of("1", "1|10"));

    verify(axisCandidates).childCandidatesFor(Dimension.CATEGORY, "1", s);
    verify(axisCandidates).childCandidatesFor(Dimension.CATEGORY, "1|10", s);
  }

  @Test
  void nullExpandedKeysFallsBackToAutoJustLikeTheNoArgOverload() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    engine.render(s, TODAY, (Set<String>) null);

    verify(axisCandidates).childCandidatesFor(Dimension.CATEGORY, "1", s);
  }

  @Test
  void effectiveExpandedKeysIntersectsTheExplicitOverrideWithRealCandidates() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    // "99" no longer exists (a since-deleted category) — dropped silently rather than kept.
    Set<String> effective = engine.effectiveExpandedKeys(s, Set.of("1", "99"));

    assertThat(effective).containsExactly("1");
  }

  @Test
  void effectiveExpandedKeysDefersToAutoWhenThereIsNoExplicitOverride() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    ReportSpec s =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    Set<String> effective = engine.effectiveExpandedKeys(s, null);

    assertThat(effective).containsExactly("1");
  }

  // ── stage e4b: Date's own expand-in-place tree on rows (reporting.md §9.1/§9.2) ─────────────

  private static ReportSpec dateRowsJanToFeb() {
    return new ReportSpec(
        List.of(Dimension.DATE),
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 2, 28))),
        false,
        false,
        true);
  }

  private void stubGridData() {
    GridData empty = new GridData(Map.of(), Map.of(), Map.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any())).thenReturn(empty);
  }

  @Test
  void dateRowsStartCollapsedUnderAutoAndFetchNoDays() {
    baseIsEur();
    stubGridData();

    engine.render(dateRowsJanToFeb(), TODAY);

    verify(gridBuilder).dateFrontierNodes(any(), eq(Set.of()));
    verify(dataFetcher, never()).fetchGridData(any(), any(), any(), eq(DateGranularity.DAY));
  }

  @Test
  void explicitDateKeysExpandOnlyRealBucketsAndFetchTheirDays() {
    baseIsEur();
    stubGridData();
    ReportSpec s = dateRowsJanToFeb();

    // "12" is a stale Category id left over from before the rows were switched to Date.
    engine.render(s, TODAY, Set.of("2026-01", "12"));

    verify(gridBuilder).dateFrontierNodes(any(), eq(Set.of("2026-01")));
    // Only January's days, over January's own range, at day granularity.
    verify(dataFetcher)
        .fetchGridData(
            argThat(
                c ->
                    c.spec().equals(s)
                        && c.today().equals(TODAY)
                        && "EUR".equals(c.baseCurrency())),
            eq(
                new RangeResolver.ResolvedRange(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))),
            any(),
            eq(DateGranularity.DAY));
    verify(dataFetcher, times(1)).fetchGridData(any(), any(), any(), eq(DateGranularity.DAY));
  }

  @Test
  void withDateOnRowsTheRememberedKeysDoNotDriveTheColumnTree() {
    // The remembered set belongs to the row tree (§9.1): with Date on rows it names Date buckets,
    // so a column-axis Category keeps auto's own rule — here, no filter, so nothing expands —
    // even when the set happens to hold a live Category id.
    baseIsEur();
    stubGridData();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense", true);
    when(axisCandidates.candidatesFor(eq(Dimension.CATEGORY), any())).thenReturn(Map.of("1", food));
    ReportSpec dateRowsCategoryColumns =
        spec(
            List.of(Dimension.DATE),
            List.of(Dimension.CATEGORY),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(dateRowsCategoryColumns, TODAY, Set.of("1"));

    verify(axisCandidates, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void effectiveExpandedKeysForDateRowsIntersectsTheOverrideWithTheBuckets() {
    Set<String> effective =
        engine.effectiveExpandedKeys(dateRowsJanToFeb(), Set.of("2026-02", "2025-12"), TODAY);

    assertThat(effective).containsExactly("2026-02");
  }

  @Test
  void effectiveExpandedKeysForDateRowsIsEmptyUnderAuto() {
    assertThat(engine.effectiveExpandedKeys(dateRowsJanToFeb(), null, TODAY)).isEmpty();
  }

  // ── ticked nodes as the axis's top level (reporting issue 08) ───────────────────────────────

  private static ReportSpec accountRowsFilteredTo(FilterLevel level, String... ids) {
    return new ReportSpec(
        List.of(Dimension.ACCOUNT),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("asset"),
        List.of(
            new ReportFilter(FilterField.ACCOUNT, level, FilterOperator.IS_ONE_OF, List.of(ids))),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  private static RawTurnoverCell turnoverOf(String key) {
    return new RawTurnoverCell(
        key, "x", "asset", "2026-01", "EUR", BigDecimal.TEN, BigDecimal.TEN, 0, 1, 1);
  }

  @Test
  void touchingKeepsEveryTickedNodeButOnlyTheRealRootsTheDataTouches() {
    // Cash-EUR is ticked and empty; BankBbb is touched; BankCcc is untouched and must not appear.
    baseIsEur();
    when(axisCandidates.candidatesFor(eq(Dimension.ACCOUNT), any()))
        .thenReturn(
            Map.of(
                "12", new TopLevelNode("12", "Cash:Cash-EUR", "asset"),
                "20", new TopLevelNode("20", "BankBbb", "asset"),
                "30", new TopLevelNode("30", "BankCcc", "asset")));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(Leg.NET, List.of(turnoverOf("20"))), Map.of(), Map.of()));

    engine.render(accountRowsFilteredTo(FilterLevel.TRANSACTION, "12"), TODAY);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, TopLevelNode>> candidates = ArgumentCaptor.forClass(Map.class);
    verify(gridBuilder)
        .frontierNodes(eq(Dimension.ACCOUNT), any(), candidates.capture(), any(), any(), any());
    assertThat(candidates.getValue()).containsOnlyKeys("12", "20");
  }

  @Test
  void withoutFilterOnTheDimensionsOwnFieldNoCandidateIsDropped() {
    baseIsEur();
    when(axisCandidates.candidatesFor(eq(Dimension.ACCOUNT), any()))
        .thenReturn(Map.of("30", new TopLevelNode("30", "BankCcc", "asset")));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("asset"));

    engine.render(s, TODAY);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, TopLevelNode>> candidates = ArgumentCaptor.forClass(Map.class);
    verify(gridBuilder)
        .frontierNodes(eq(Dimension.ACCOUNT), any(), candidates.capture(), any(), any(), any());
    assertThat(candidates.getValue()).containsOnlyKeys("30");
  }
}
