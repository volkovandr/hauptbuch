package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/** Turns a {@link ReportGrid} into the table renderer's display-ready {@link ReportTableView}. */
final class ReportTableViewAssembler {

  private static final ReportTableView.CellText BLANK = new ReportTableView.CellText("", null);

  private ReportTableViewAssembler() {}

  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency) {
    List<String> columnLabels = grid.columns().stream().map(AxisNode::label).toList();
    List<ReportTableView.RowView> rows = new ArrayList<>();
    for (int i = 0; i < grid.rows().size(); i++) {
      List<ReportTableView.CellText> cells =
          grid.cells().get(i).stream().map(c -> format(c, baseCurrency)).toList();
      ReportTableView.CellText rowTotal =
          spec.rowTotals() ? format(grid.rowTotals().get(i), baseCurrency) : BLANK;
      rows.add(new ReportTableView.RowView(grid.rows().get(i).label(), cells, rowTotal));
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
        columnLabels,
        rows,
        spec.rowTotals(),
        spec.columnTotals(),
        columnTotals,
        grandTotal);
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
