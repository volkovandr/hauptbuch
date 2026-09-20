package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportTableViewAssembler}'s pass-through of the "scope misses
 * the dimension" message (reporting.md §6.1) from {@link ReportGrid#scopeMismatch()} — computed
 * once by {@link ReportGridBuilder} ({@code ReportGridBuilderTest}'s job) into {@link
 * ReportTableView} — plus cell formatting itself: value/blank/illegal text, and the §11a.7
 * help-marker text an {@link Cell.Illegal} carries for every {@link Cell.Reason}.
 */
class ReportTableViewAssemblerTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final LocalDate END = LocalDate.of(2026, 1, 31);

  private static ReportSpec spec() {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(new RangeEndpoint.Literal(START), new RangeEndpoint.Literal(END)),
        false,
        false,
        true);
  }

  @Test
  void carriesTheGridsScopeMismatchMessage() {
    String message = "Category covers income and expense accounts; neither is in scope.";
    ReportGrid grid =
        new ReportGrid(
            List.of(), List.of(), List.of(), List.of(), List.of(), Cell.BLANK, START, END, message);

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    assertThat(view.scopeMismatch()).isEqualTo(message);
  }

  @Test
  void isNullWhenTheGridCarriesNone() {
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("1", "Food")),
            List.of(new AxisNode("total", "Total")),
            List.of(List.of(new Cell.Value(new BigDecimal("50"), "EUR"))),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END,
            null);

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    assertThat(view.scopeMismatch()).isNull();
  }

  @Test
  void valueCellFormatsWithNoHelp() {
    ReportGrid grid = gridOf(new Cell.Value(new BigDecimal("50"), "EUR"));

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    ReportTableView.CellText cell = view.rows().get(0).cells().get(0);
    assertThat(cell.text()).isEqualTo("50,00");
    assertThat(cell.help()).isNull();
  }

  @Test
  void blankCellRendersEmptyTextWithNoHelp() {
    ReportGrid grid = gridOf(Cell.BLANK);

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    ReportTableView.CellText cell = view.rows().get(0).cells().get(0);
    assertThat(cell.text()).isEmpty();
    assertThat(cell.help()).isNull();
  }

  @Test
  void illegalCellRendersDashWithHelpNamingItsReason() {
    ReportGrid grid = gridOf(new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    ReportTableView.CellText cell = view.rows().get(0).cells().get(0);
    assertThat(cell.text()).isEqualTo("—");
    assertThat(cell.help()).contains("more than one native currency");
  }

  @Test
  void everyIllegalReasonHasItsOwnDistinctHelpText() {
    String timeAxis = helpFor(Cell.Reason.TIME_AXIS_BALANCE);
    String multiCurrency = helpFor(Cell.Reason.MULTI_CURRENCY);
    String crossTag = helpFor(Cell.Reason.CROSS_TAG_TOTAL);
    String missingRate = helpFor(Cell.Reason.MISSING_RATE);
    String multiMeasure = helpFor(Cell.Reason.MULTI_MEASURE_TOTAL);

    assertThat(Set.of(timeAxis, multiCurrency, crossTag, missingRate, multiMeasure)).hasSize(5);
  }

  private static String helpFor(Cell.Reason reason) {
    ReportGrid grid = gridOf(new Cell.Illegal(reason));
    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");
    String help = view.rows().get(0).cells().get(0).help();
    assertThat(help).isNotBlank();
    return help;
  }

  private static ReportGrid gridOf(Cell cell) {
    return new ReportGrid(
        List.of(new AxisNode("1", "Food")),
        List.of(new AxisNode("total", "Total")),
        List.of(List.of(cell)),
        List.of(),
        List.of(),
        Cell.BLANK,
        START,
        END,
        null);
  }
}
