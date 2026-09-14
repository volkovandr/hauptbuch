package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportTableViewAssembler}'s pass-through of the "scope misses
 * the dimension" message (reporting.md §6.1) from {@link ReportGrid#scopeMismatch()} — computed
 * once by {@link ReportGridBuilder} ({@code ReportGridBuilderTest}'s job) into {@link
 * ReportTableView}. Cell formatting itself is exercised end to end by the Preset integration tests
 * ({@code ReportControllerIntegrationTest}).
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
}
