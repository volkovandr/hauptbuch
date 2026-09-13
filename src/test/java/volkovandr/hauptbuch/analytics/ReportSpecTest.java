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
  void rejectsMoreThanOneRowDimension() {
    assertThatThrownBy(() -> spec(List.of(Dimension.CATEGORY, Dimension.TAG), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMoreThanOneColumnDimension() {
    assertThatThrownBy(() -> spec(List.of(), List.of(Dimension.CATEGORY, Dimension.TAG), List.of()))
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
}
