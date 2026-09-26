package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportTableViewAssembler}'s pass-through of the "scope misses
 * the dimension" message (reporting.md §6.1) from {@link ReportGrid#refusalMessage()} — computed
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

    assertThat(view.refusalMessage()).isEqualTo(message);
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

    assertThat(view.refusalMessage()).isNull();
  }

  @Test
  void columnsCarryTheirDepthAndWhetherTheyAreAnExpandedParent() {
    // Column headers show hierarchy (stage e5): an expanded parent column (a subtotal over the
    // children right after it) and its children must be told apart in the rendered header.
    ReportGrid grid =
        new ReportGrid(
            List.of(new AxisNode("total", "Total")),
            List.of(
                new AxisNode("1", "Cash", 0, true, null, true),
                new AxisNode("1|10", "Cash (EUR)", 1, false, "1"),
                new AxisNode("2", "Savings", 0, true, null, false)),
            List.of(List.of(Cell.BLANK, Cell.BLANK, Cell.BLANK)),
            List.of(),
            List.of(),
            Cell.BLANK,
            START,
            END,
            null);

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    assertThat(view.columns())
        .containsExactly(
            new ReportTableView.ColumnView("Cash", 0, true),
            new ReportTableView.ColumnView("Cash (EUR)", 1, false),
            new ReportTableView.ColumnView("Savings", 0, false));
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

  // ── row-tree metadata and toggle URLs (plan stage e2, issue 02) ────────────────────────────────

  @Test
  void fourArgOverloadOffersNoToggle() {
    ReportTableView view =
        ReportTableViewAssembler.assemble("Title", spec(), foodWithRestaurants(), "EUR");

    assertThat(view.rows()).extracting(ReportTableView.RowView::toggleUrl).containsOnlyNulls();
  }

  @Test
  void onlyExpandableRowsGetTheToggleUrl() {
    ReportTableView view =
        ReportTableViewAssembler.assemble(
            "Title", spec(), foodWithRestaurants(), "EUR", RowToggle.persisted(42L));

    assertThat(view.togglePersists()).isTrue();
    assertThat(view.rows().get(0).toggleUrl()).isEqualTo("/reports/42/expand?node=1");
    assertThat(view.rows().get(1).toggleUrl()).isNull();
  }

  @Test
  void ephemeralToggleStartsFromTheRowsAutoExpanded() {
    // Food is expanded on screen (auto) with no explicit set yet: its toggle collapses it.
    ReportTableView view =
        ReportTableViewAssembler.assemble(
            "Title",
            spec(),
            foodWithRestaurants(),
            "EUR",
            RowToggle.ephemeral("/reports/new", new LinkedMultiValueMap<>(), null));

    assertThat(view.togglePersists()).isFalse();
    assertThat(view.rows().get(0).toggleUrl()).isEqualTo("/reports/new?expanded=");
  }

  @Test
  void eachRowCarriesItsOwnNodesKeyDepthExpandableAndExpanded() {
    ReportGrid grid = foodWithRestaurants();

    ReportTableView view = ReportTableViewAssembler.assemble("Title", spec(), grid, "EUR");

    ReportTableView.RowView parent = view.rows().get(0);
    assertThat(parent.key()).isEqualTo("1");
    assertThat(parent.depth()).isZero();
    assertThat(parent.expandable()).isTrue();
    assertThat(parent.expanded()).isTrue();

    ReportTableView.RowView child = view.rows().get(1);
    assertThat(child.key()).isEqualTo("1|10");
    assertThat(child.depth()).isEqualTo(1);
    assertThat(child.expandable()).isFalse();
    assertThat(child.expanded()).isFalse();
  }

  private static ReportGrid foodWithRestaurants() {
    return new ReportGrid(
        List.of(
            new AxisNode("1", "Food", 0, true, null, true),
            new AxisNode("1|10", "Restaurants", 1, false, "1", false)),
        List.of(new AxisNode("total", "Total")),
        List.of(
            List.of(new Cell.Value(new BigDecimal("35"), "EUR")),
            List.of(new Cell.Value(new BigDecimal("30"), "EUR"))),
        List.of(),
        List.of(),
        Cell.BLANK,
        START,
        END,
        null);
  }

  // ── drill-down (reporting.md §12) ─────────────────────────────────────────

  /** One Food row × Jan and Feb, with both totals on. */
  private static ReportGrid drillGrid(Cell foodFeb, Cell columnTotalJan) {
    return new ReportGrid(
        List.of(new AxisNode("1", "Food")),
        List.of(new AxisNode("2026-01", "Jan 2026"), new AxisNode("2026-02", "Feb 2026")),
        List.of(List.of(new Cell.Value(new BigDecimal("20"), "EUR"), foodFeb)),
        List.of(new Cell.Illegal(Cell.Reason.MISSING_RATE)),
        List.of(columnTotalJan, Cell.BLANK),
        Cell.BLANK,
        START,
        END,
        null);
  }

  private static ReportSpec byMonthWithTotals() {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(new RangeEndpoint.Literal(START), new RangeEndpoint.Literal(END)),
        true,
        true,
        true);
  }

  @Test
  void everyFigureWithPostingsBehindItOpensItsDrillDown() {
    ReportGrid grid = drillGrid(Cell.BLANK, new Cell.Illegal(Cell.Reason.CROSS_TAG_TOTAL));

    ReportTableView view =
        ReportTableViewAssembler.assemble(
            "Title", byMonthWithTotals(), grid, "EUR", null, new LinkedMultiValueMap<>());

    ReportTableView.RowView food = view.rows().get(0);
    assertThat(food.cells())
        .extracting(ReportTableView.CellText::drill)
        .containsExactly("0/1/2026-01", null); // a blank cell has no postings
    // A missing rate's — still has its postings; overlapping tags' — has no one posting set.
    assertThat(food.rowTotal().drill()).isEqualTo("0/1/");
    assertThat(view.columnTotals())
        .extracting(ReportTableView.CellText::drill)
        .containsExactly(null, null);
    assertThat(view.grandTotal().drill()).isNull();
  }

  @Test
  void noFigureOpensDrillDownWhenTheTableOffersNone() {
    ReportGrid grid = drillGrid(Cell.BLANK, new Cell.Value(new BigDecimal("20"), "EUR"));

    ReportTableView view =
        ReportTableViewAssembler.assemble("Title", byMonthWithTotals(), grid, "EUR", null);

    assertThat(view.drillParams()).isNull();
    assertThat(view.rows().get(0).cells().get(0).drill()).isNull();
    assertThat(view.columnTotals().get(0).drill()).isNull();
  }
}
