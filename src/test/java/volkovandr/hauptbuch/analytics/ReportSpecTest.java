package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportSpec}'s own decidable-without-the-DB validation — the
 * per-axis dimension caps (stage a's rows/columns, stage b's series) and the rows/series mutual
 * exclusion (reporting.md §3, {@link ReportSpec}'s own javadoc).
 */
class ReportSpecTest {

  private static final DateRange A_RANGE =
      new DateRange(
          new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
          new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)));

  private static ReportSpec spec(
      List<Dimension> rows, List<Dimension> columns, List<Dimension> series) {
    return new ReportSpec(
        rows,
        columns,
        series,
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        A_RANGE,
        false,
        false,
        true);
  }

  @Test
  void rejectsMoreThanTwoRowDimensions() {
    assertThatThrownBy(
            () ->
                spec(
                    List.of(Dimension.CATEGORY, Dimension.TAG, Dimension.CURRENCY),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMoreThanTwoColumnDimensions() {
    assertThatThrownBy(
            () ->
                spec(
                    List.of(),
                    List.of(Dimension.CATEGORY, Dimension.TAG, Dimension.CURRENCY),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTwoDifferentRowDimensionsAsNesting() {
    assertThatCode(() -> spec(List.of(Dimension.TAG, Dimension.CATEGORY), List.of(), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsTwoDifferentColumnDimensionsAsNesting() {
    assertThatCode(() -> spec(List.of(), List.of(Dimension.TAG, Dimension.CATEGORY), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsTheSameDimensionRepeatedOnRows() {
    assertThatThrownBy(
            () -> spec(List.of(Dimension.CATEGORY, Dimension.CATEGORY), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTheSameDimensionRepeatedOnColumns() {
    assertThatThrownBy(() -> spec(List.of(), List.of(Dimension.TAG, Dimension.TAG), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDateCombinedWithAnotherDimensionOnRows() {
    assertThatThrownBy(
            () -> spec(List.of(Dimension.DATE, Dimension.CATEGORY), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDateCombinedWithAnotherDimensionOnColumns() {
    assertThatThrownBy(
            () -> spec(List.of(), List.of(Dimension.CATEGORY, Dimension.DATE), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMoreThanOneSeriesDimension() {
    assertThatThrownBy(
            () -> spec(List.of(), List.of(), List.of(Dimension.DATE, Dimension.CATEGORY)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRowDimensionCombinedWithSeriesDimension() {
    assertThatThrownBy(() -> spec(List.of(Dimension.CATEGORY), List.of(), List.of(Dimension.DATE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsSeriesDimensionAloneWithNoRows() {
    assertThatCode(() -> spec(List.of(), List.of(Dimension.CATEGORY), List.of(Dimension.DATE)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsRowDimensionAloneWithNoSeries() {
    assertThatCode(() -> spec(List.of(Dimension.CATEGORY), List.of(Dimension.DATE), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsNoMeasures() {
    assertThatThrownBy(
            () ->
                new ReportSpec(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Scope.ofTypes("expense"),
                    List.of(),
                    A_RANGE,
                    false,
                    false,
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTwoFiltersOnTheSameField() {
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.CATEGORY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of("1")),
            new ReportFilter(
                FilterField.CATEGORY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of("2")));

    assertThatThrownBy(
            () ->
                new ReportSpec(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
                    Scope.ofTypes("expense"),
                    filters,
                    A_RANGE,
                    false,
                    false,
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOneFilterPerDistinctField() {
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.CATEGORY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of("1")),
            new ReportFilter(
                FilterField.PAYEE,
                FilterLevel.TRANSACTION,
                FilterOperator.IS_ONE_OF,
                List.of("2")));

    assertThatCode(
            () ->
                new ReportSpec(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
                    Scope.ofTypes("expense"),
                    filters,
                    A_RANGE,
                    false,
                    false,
                    true))
        .doesNotThrowAnyException();
  }
}
