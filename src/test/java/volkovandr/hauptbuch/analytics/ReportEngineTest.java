package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportEngine}'s own orchestration and validation — the
 * unsupported-feature guards, axis planning, and that it wires {@link ReportDataFetcher} and {@link
 * ReportGridBuilder} together — with both mocked. Which repository query a dimension maps to is
 * {@link ReportDataFetcherTest}'s job; grid assembly is {@link ReportGridBuilderTest}'s.
 */
class ReportEngineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

  private final SettingsService settingsService = mock();
  private final ReportDataFetcher dataFetcher = mock();
  private final ReportGridBuilder gridBuilder = mock();
  private final ReportEngine engine = new ReportEngine(settingsService, dataFetcher, gridBuilder);

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
  void rejectsClosingBalanceOnPayeeDimension() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.PAYEE),
            List.of(),
            Measure.closingBalance(PresentationCurrency.BASE),
            Scope.ofTypes("expense"));

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsTwoDifferentNonDateDimensionsOnRowsAndColumns() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.TAG),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsDateOnBothRowsAndColumns() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.DATE),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsClosingBalanceOnTagDimension() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.TAG),
            List.of(),
            Measure.closingBalance(PresentationCurrency.BASE),
            Scope.ofTypes("asset"));

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── orchestration ─────────────────────────────────────────────────────────

  @Test
  void seriesFillsTheRowSlotWhenRowsIsEmpty() {
    baseIsEur();
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
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
    verify(dataFetcher).candidatesFor(Dimension.CATEGORY, List.of("expense"), s.scope());
  }

  @Test
  void resolvesAxesFetchesDataAndDelegatesToTheGridBuilder() {
    baseIsEur();
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY);

    verify(dataFetcher).candidatesFor(Dimension.CATEGORY, List.of("expense"), s.scope());
    verify(gridBuilder).build(eq(s), any(), any(), any(), any(), any(), eq("EUR"), any());
  }

  // ── stage e nesting (reporting.md §3, §9) ────────────────────────────────

  @Test
  void expandedFetchesSameDimensionChildCandidatesForEveryTopLevelNode() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY, RowExpansion.EXPANDED);

    verify(dataFetcher).childCandidatesFor(Dimension.CATEGORY, "1", s.scope());
  }

  @Test
  void collapsedNeverFetchesEitherChildSourceEvenWhenTheAxisNestsTwoDimensions() {
    baseIsEur();
    TopLevelNode trip = new TopLevelNode("1", "Trip", null);
    when(dataFetcher.candidatesFor(eq(Dimension.TAG), any(), any())).thenReturn(Map.of("1", trip));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
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

    verify(dataFetcher, never()).candidatesFor(eq(Dimension.CATEGORY), any(), any());
    verify(dataFetcher, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void expandedFetchesTheInnerDimensionsCandidatesForCrossDimensionNesting() {
    baseIsEur();
    TopLevelNode trip = new TopLevelNode("1", "Trip", null);
    when(dataFetcher.candidatesFor(eq(Dimension.TAG), any(), any())).thenReturn(Map.of("1", trip));
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any())).thenReturn(Map.of());
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
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

    verify(dataFetcher).candidatesFor(Dimension.CATEGORY, List.of("expense"), s.scope());
    verify(dataFetcher, never()).childCandidatesFor(any(), any(), any());
  }

  // ── stage e2: a saved Report's remembered per-node expansion (reporting.md §9.1) ────────────

  @Test
  void explicitExpandedKeysFetchesChildrenOnlyForTheKeysNamed() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    TopLevelNode fuel = new TopLevelNode("2", "Fuel", "expense");
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food, "2", fuel));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new GridData(Map.of(), Map.of(), Map.of()));
    ReportSpec s =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
            Scope.ofTypes("expense"));

    engine.render(s, TODAY, Set.of("1"));

    verify(dataFetcher).childCandidatesFor(Dimension.CATEGORY, "1", s.scope());
    verify(dataFetcher, never()).childCandidatesFor(eq(Dimension.CATEGORY), eq("2"), any());
  }

  @Test
  void explicitlyEmptyExpandedKeysFetchesNoChildrenEvenWhenAutoWouldHaveExpanded() {
    baseIsEur();
    // A filter selecting exactly one node is auto's own "start expanded" trigger (§9.2) — an
    // explicit (if empty) override must still win over it, since the owner hand-collapsed it.
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
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

    verify(dataFetcher, never()).childCandidatesFor(any(), any(), any());
  }

  @Test
  void nullExpandedKeysFallsBackToAutoJustLikeTheNoArgOverload() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food));
    when(gridBuilder.frontierNodes(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any(), any()))
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

    verify(dataFetcher).childCandidatesFor(Dimension.CATEGORY, "1", s.scope());
  }

  @Test
  void effectiveExpandedKeysIntersectsTheExplicitOverrideWithRealCandidates() {
    baseIsEur();
    TopLevelNode food = new TopLevelNode("1", "Food", "expense");
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food));
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
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any()))
        .thenReturn(Map.of("1", food));
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
}
