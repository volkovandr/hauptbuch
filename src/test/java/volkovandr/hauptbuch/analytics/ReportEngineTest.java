package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
  void rejectsFiltersAsNotYetWired() {
    baseIsEur();
    ReportSpec s =
        new ReportSpec(
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

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsScopeSubtreeRestriction() {
    baseIsEur();
    Scope scope = new Scope(java.util.Set.of("expense"), List.of(1L), true, false);
    ReportSpec s =
        spec(List.of(), List.of(), Measure.turnover(PresentationCurrency.BASE, Leg.NET), scope);

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsCountMeasures() {
    baseIsEur();
    ReportSpec s = spec(List.of(), List.of(), Measure.countPostings(), Scope.ofTypes("expense"));

    assertThatThrownBy(() -> engine.render(s, TODAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAnUnwiredDimension() {
    baseIsEur();
    ReportSpec s =
        spec(
            List.of(Dimension.PAYEE),
            List.of(),
            Measure.turnover(PresentationCurrency.BASE, Leg.NET),
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
  void resolvesAxesFetchesDataAndDelegatesToTheGridBuilder() {
    baseIsEur();
    when(dataFetcher.candidatesFor(eq(Dimension.CATEGORY), any(), any())).thenReturn(Map.of());
    when(gridBuilder.axisNodes(any(), any(), any())).thenReturn(List.of());
    when(dataFetcher.fetchGridData(any(), any(), any(), any(), any(), any(), any()))
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
}
