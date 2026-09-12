package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/** Turns a {@link ReportGrid} into the table renderer's display-ready {@link ReportTableView}. */
final class ReportTableViewAssembler {

  private ReportTableViewAssembler() {}

  static ReportTableView assemble(
      String title, ReportSpec spec, ReportGrid grid, String baseCurrency) {
    List<String> columnLabels = grid.columns().stream().map(AxisNode::label).toList();
    List<ReportTableView.RowView> rows = new ArrayList<>();
    for (int i = 0; i < grid.rows().size(); i++) {
      List<String> cells = grid.cells().get(i).stream().map(c -> format(c, baseCurrency)).toList();
      String rowTotal = spec.rowTotals() ? format(grid.rowTotals().get(i), baseCurrency) : "";
      rows.add(new ReportTableView.RowView(grid.rows().get(i).label(), cells, rowTotal));
    }
    List<String> columnTotals =
        spec.columnTotals()
            ? grid.columnTotals().stream().map(c -> format(c, baseCurrency)).toList()
            : List.of();
    String grandTotal =
        spec.rowTotals() && spec.columnTotals() ? format(grid.grandTotal(), baseCurrency) : "";

    return new ReportTableView(
        title,
        ScopeHeaderText.render(spec.scope()),
        grid.resolvedStart(),
        grid.resolvedEnd(),
        columnLabels,
        rows,
        spec.rowTotals(),
        spec.columnTotals(),
        columnTotals,
        grandTotal);
  }

  private static String format(Cell cell, String baseCurrency) {
    if (cell instanceof Cell.Blank) {
      return "";
    }
    if (cell instanceof Cell.Illegal) {
      return "—";
    }
    Cell.Value value = (Cell.Value) cell;
    return MoneyFormat.display(MoneyFactory.of(value.amount(), value.currencyCode()), baseCurrency);
  }
}
