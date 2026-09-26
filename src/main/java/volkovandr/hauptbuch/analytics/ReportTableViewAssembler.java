package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/** Turns a {@link ReportGrid} into the table renderer's display-ready {@link ReportTableView}. */
final class ReportTableViewAssembler {

  private static final ReportTableView.CellText BLANK = new ReportTableView.CellText("", null);

  private ReportTableViewAssembler() {}

  /**
   * {@link #assemble(String, ReportSpec, ReportGrid, String, RowToggle)} with no toggle control.
   */
  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency) {
    return assemble(title, spec, grid, baseCurrency, null);
  }

  /**
   * {@link #assemble(String, ReportSpec, ReportGrid, String, RowToggle, MultiValueMap)} offering no
   * drill-down.
   */
  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency, RowToggle rowToggle) {
    return assemble(title, spec, grid, baseCurrency, rowToggle, null);
  }

  /**
   * Turns a {@link ReportGrid} into a display-ready {@link ReportTableView}.
   *
   * @param rowToggle what the row-tree's expand/collapse controls do (plan stage e2, issue 02), or
   *     {@code null} for none — see {@link RowToggle}
   * @param drillParams the Report's spec and expansion as the drill-down form's hidden fields
   *     (reporting.md §12), or {@code null} for no drill-down; with them, every figure {@link
   *     DrillDown.isDrillable} carries its {@link CellAddress#token}
   */
  static ReportTableView assemble(
      String title,
      ReportSpec spec,
      ReportGrid grid,
      String baseCurrency,
      RowToggle rowToggle,
      MultiValueMap<String, String> drillParams) {
    Figures figures = new Figures(spec, grid, baseCurrency, drillParams != null);
    List<ReportTableView.ColumnView> columns =
        grid.columns().stream()
            .map(
                node ->
                    new ReportTableView.ColumnView(
                        node.label(), node.depth(), node.expandable() && node.expanded()))
            .toList();
    Set<String> onScreen =
        grid.rows().stream()
            .filter(AxisNode::expanded)
            .map(AxisNode::key)
            .collect(Collectors.toSet());
    List<ReportTableView.RowView> rows = new ArrayList<>();
    for (int i = 0; i < grid.rows().size(); i++) {
      AxisNode node = grid.rows().get(i);
      rows.add(
          new ReportTableView.RowView(
              node.key(),
              node.label(),
              node.depth(),
              node.expandable(),
              node.expanded(),
              rowToggle != null && node.expandable()
                  ? rowToggle.urlFor(node.key(), onScreen)
                  : null,
              figures.bodyRow(i),
              figures.rowTotal(i)));
    }

    return new ReportTableView(
        title,
        ScopeHeaderText.render(spec.scope()),
        grid.refusalMessage(),
        grid.resolvedStart(),
        grid.resolvedEnd(),
        columns,
        rows,
        spec.rowTotals(),
        spec.columnTotals(),
        figures.columnTotals(),
        figures.grandTotal(),
        drillParams,
        rowToggle != null && rowToggle.persists());
  }

  /**
   * The grid's figures as display text, each carrying its drill-down token when the table offers
   * drill-down (reporting.md §12) and the figure {@link DrillDown.isDrillable} for its measure; a
   * total the Report does not show is blank.
   */
  private record Figures(
      ReportSpec spec, ReportGrid grid, String baseCurrency, boolean drillOffered) {

    List<ReportTableView.CellText> bodyRow(int row) {
      return IntStream.range(0, grid.columns().size())
          .mapToObj(
              column ->
                  text(
                      grid.cells().get(row).get(column),
                      CellAddress.forBody(spec, grid, row, column)))
          .toList();
    }

    ReportTableView.CellText rowTotal(int row) {
      return spec.rowTotals()
          ? text(grid.rowTotals().get(row), CellAddress.forRowTotal(grid, row))
          : BLANK;
    }

    List<ReportTableView.CellText> columnTotals() {
      if (!spec.columnTotals()) {
        return List.of();
      }
      return IntStream.range(0, grid.columnTotals().size())
          .mapToObj(
              column ->
                  text(
                      grid.columnTotals().get(column),
                      CellAddress.forColumnTotal(spec, grid, column)))
          .toList();
    }

    ReportTableView.CellText grandTotal() {
      return spec.rowTotals() && spec.columnTotals()
          ? text(grid.grandTotal(), CellAddress.forGrandTotal())
          : BLANK;
    }

    private ReportTableView.CellText text(Cell cell, CellAddress address) {
      ReportTableView.CellText text = format(cell, baseCurrency);
      Measure measure = spec.measures().get(address.measureIndex());
      return drillOffered && DrillDown.isDrillable(cell, measure)
          ? text.withDrill(address.token())
          : text;
    }
  }

  /** {@code cell} as display text — shared with the drill-down's running column. */
  static ReportTableView.CellText format(Cell cell, String baseCurrency) {
    if (cell instanceof Cell.Blank) {
      return BLANK;
    }
    if (cell instanceof Cell.Illegal illegal) {
      return new ReportTableView.CellText("—", illegalCellHelp(illegal.reason()));
    }
    if (cell instanceof Cell.Count count) {
      return new ReportTableView.CellText(String.valueOf(count.count()), null);
    }
    Cell.Value value = (Cell.Value) cell;
    return new ReportTableView.CellText(
        MoneyFormat.display(MoneyFactory.of(value.amount(), value.currencyCode()), baseCurrency),
        null);
  }

  /** The §11a.7 help-marker text for one of §7.2's illegal-cell reasons. */
  private static String illegalCellHelp(Cell.Reason reason) {
    return switch (reason) {
      case TIME_AXIS_BALANCE -> "A balance is a snapshot — it can't be summed across time.";
      case MULTI_CURRENCY -> "This spans more than one native currency, so it can't be added up.";
      case CROSS_TAG_TOTAL -> "Tags overlap, so a total here would double-count some postings.";
      case MISSING_RATE -> "No exchange rate is recorded for this date.";
      case MULTI_MEASURE_TOTAL -> "These are different presentations of one figure, not addable.";
    };
  }
}
