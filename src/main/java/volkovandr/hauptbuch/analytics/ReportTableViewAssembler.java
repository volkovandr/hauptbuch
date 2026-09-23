package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/** Turns a {@link ReportGrid} into the table renderer's display-ready {@link ReportTableView}. */
final class ReportTableViewAssembler {

  private static final ReportTableView.CellText BLANK = new ReportTableView.CellText("", null);

  private ReportTableViewAssembler() {}

  /** {@link #assemble(String, ReportSpec, ReportGrid, String, Long)} with no toggle control. */
  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency) {
    return assemble(title, spec, grid, baseCurrency, null);
  }

  /**
   * Turns a {@link ReportGrid} into a display-ready {@link ReportTableView}.
   *
   * @param toggleReportId the saved Report id the row-tree's expand/collapse links post to (plan
   *     stage e2), carried straight onto {@link ReportTableView#toggleReportId} — see its own
   *     javadoc for when this is {@code null}
   */
  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency, Long toggleReportId) {
    List<ReportTableView.ColumnView> columns =
        grid.columns().stream()
            .map(
                node ->
                    new ReportTableView.ColumnView(
                        node.label(), node.depth(), node.expandable() && node.expanded()))
            .toList();
    List<ReportTableView.RowView> rows = new ArrayList<>();
    for (int i = 0; i < grid.rows().size(); i++) {
      AxisNode node = grid.rows().get(i);
      List<ReportTableView.CellText> cells =
          grid.cells().get(i).stream().map(c -> format(c, baseCurrency)).toList();
      ReportTableView.CellText rowTotal =
          spec.rowTotals() ? format(grid.rowTotals().get(i), baseCurrency) : BLANK;
      rows.add(
          new ReportTableView.RowView(
              node.key(),
              node.label(),
              node.depth(),
              node.expandable(),
              node.expanded(),
              cells,
              rowTotal));
    }
    List<ReportTableView.CellText> columnTotals =
        spec.columnTotals()
            ? grid.columnTotals().stream().map(c -> format(c, baseCurrency)).toList()
            : List.of();
    ReportTableView.CellText grandTotal =
        spec.rowTotals() && spec.columnTotals() ? format(grid.grandTotal(), baseCurrency) : BLANK;

    return new ReportTableView(
        title,
        ScopeHeaderText.render(spec.scope()),
        grid.scopeMismatch(),
        grid.resolvedStart(),
        grid.resolvedEnd(),
        columns,
        rows,
        spec.rowTotals(),
        spec.columnTotals(),
        columnTotals,
        grandTotal,
        toggleReportId);
  }

  private static ReportTableView.CellText format(Cell cell, String baseCurrency) {
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
