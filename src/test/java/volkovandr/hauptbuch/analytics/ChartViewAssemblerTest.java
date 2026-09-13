package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ChartViewAssembler} — small multiples vs. series-as-legend
 * (reporting.md §3, {@link ReportSpec}'s javadoc) and the pie negative-measure refusal (§7.4). Grid
 * assembly itself is {@link ReportGridBuilderTest}'s job; this class only turns an already-built
 * {@link ReportGrid} into panels.
 */
class ChartViewAssemblerTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final LocalDate END = LocalDate.of(2026, 1, 31);

  private static Cell value(String amount) {
    return new Cell.Value(new BigDecimal(amount), "EUR");
  }

  private static ReportSpec specWithRows(List<Dimension> rows, List<Dimension> series) {
    return new ReportSpec(
        rows,
        List.of(Dimension.DATE),
        series,
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(new RangeEndpoint.Literal(START), new RangeEndpoint.Literal(END)),
        false,
        false,
        true);
  }

  @Test
  void seriesDimensionRendersOnePanelWithOneLinePerRow() {
    ReportSpec spec = specWithRows(List.of(), List.of(Dimension.DATE));
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("2026-01", "Jan 2026"), new AxisNode("2026-02", "Feb 2026")),
            List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel")),
            List.of(List.of(value("50"), value("20")), List.of(value("40"), value("30"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    ChartView view = ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.BAR, false);

    assertThat(view.refusalMessage()).isNull();
    assertThat(view.panels()).hasSize(1);
    assertThat(view.panels().get(0).svg()).contains("Jan 2026").contains("Feb 2026");
  }

  @Test
  void rowDimensionRendersOnePanelPerRow() {
    ReportSpec spec = specWithRows(List.of(Dimension.CATEGORY), List.of());
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel")),
            List.of(new AxisNode("2026-01", "Jan 2026")),
            List.of(List.of(value("50")), List.of(value("20"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    ChartView view = ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.LINE, false);

    assertThat(view.panels()).hasSize(2);
    assertThat(view.panels()).extracting(ChartPanel::label).containsExactly("Food", "Fuel");
  }

  @Test
  void noRowOrSeriesDimensionRendersOneUncaptionedPanel() {
    ReportSpec spec = specWithRows(List.of(), List.of());
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("total", "Total")),
            List.of(new AxisNode("2026-01", "Jan 2026")),
            List.of(List.of(value("100"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    ChartView view = ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.LINE, true);

    assertThat(view.panels()).hasSize(1);
    assertThat(view.panels().get(0).label()).isEmpty();
  }

  @Test
  void pieRefusesNegativeValueRatherThanDrawingWedge() {
    ReportSpec spec = specWithRows(List.of(), List.of());
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("total", "Total")),
            List.of(new AxisNode("1", "Food"), new AxisNode("2", "Refund")),
            List.of(List.of(value("50"), value("-10"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    ChartView view = ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.PIE, false);

    assertThat(view.refusalMessage()).isNotBlank();
    assertThat(view.panels()).isEmpty();
  }

  @Test
  void pieIsAlwaysSmallMultiplesEvenWithoutRowDimension() {
    ReportSpec spec = specWithRows(List.of(), List.of(Dimension.CATEGORY));
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("1", "Food"), new AxisNode("2", "Fuel")),
            List.of(new AxisNode("total", "Total")),
            List.of(List.of(value("50")), List.of(value("20"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    ChartView view = ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.PIE, false);

    assertThat(view.panels()).hasSize(2);
  }

  @Test
  void rejectsMoreThanOneMeasure() {
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(),
            List.of(),
            List.of(
                Measure.closingBalance(PresentationCurrency.BASE),
                Measure.closingBalance(PresentationCurrency.ACCOUNT)),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(new RangeEndpoint.Literal(START), new RangeEndpoint.Literal(END)),
            false,
            false,
            true);
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("total", "Total")),
            List.of(new AxisNode("total", "Total")),
            List.of(List.of(value("1"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END);

    assertThatThrownBy(
            () -> ChartViewAssembler.assemble("Title", spec, grid, "EUR", Renderer.LINE, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
