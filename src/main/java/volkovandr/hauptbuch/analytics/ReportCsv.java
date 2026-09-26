package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import volkovandr.hauptbuch.shared.MoneyFactory;

/**
 * A rendered {@link ReportGrid} as CSV (reporting.md §13), for a spreadsheet rather than a reader:
 * ISO dates instead of display labels, plain decimal points rounded to each currency's minor units
 * instead of German formatting, one column per measure, and exactly the rows, columns and totals
 * the grid carries — so "as shown" and "raw" differ only in the grid they are given. A figure that
 * is {@code —} on screen is {@code —} here too; a blank one is empty.
 */
final class ReportCsv {

  private static final String LINE_END = "\r\n";
  private static final String TOTAL = "Total";
  private static final String INDENT = "  ";

  private ReportCsv() {}

  /** {@code grid}, rendered from {@code spec}, as CSV text with CRLF line ends. */
  static String write(ReportSpec spec, ReportGrid grid) {
    if (grid.refusalMessage() != null && grid.rows().isEmpty()) {
      return field(grid.refusalMessage()) + LINE_END;
    }
    boolean rowTotals = !grid.rowTotals().isEmpty();
    List<List<String>> lines = new ArrayList<>();
    lines.add(header(spec, grid, rowTotals));
    IntStream.range(0, grid.rows().size())
        .mapToObj(
            row ->
                line(
                    rowLabel(spec, grid.rows().get(row)),
                    grid.cells().get(row),
                    rowTotals ? grid.rowTotals().get(row) : null))
        .forEach(lines::add);
    if (!grid.columnTotals().isEmpty()) {
      lines.add(line(TOTAL, grid.columnTotals(), rowTotals ? grid.grandTotal() : null));
    }
    return lines.stream()
        .map(line -> line.stream().map(ReportCsv::field).collect(Collectors.joining(",")))
        .collect(Collectors.joining(LINE_END, "", LINE_END));
  }

  private static List<String> header(ReportSpec spec, ReportGrid grid, boolean rowTotals) {
    List<String> header = new ArrayList<>();
    header.add(rowHeader(spec));
    List<AxisNode> columns = grid.columns();
    IntStream.range(0, columns.size())
        .forEach(i -> header.add(columnHeader(spec, columns.get(i), i)));
    if (rowTotals) {
      header.add(TOTAL);
    }
    return header;
  }

  /** One line: its label, its figures, and its total when the grid shows one ({@code null}). */
  private static List<String> line(String label, List<Cell> cells, Cell total) {
    List<String> line = new ArrayList<>();
    line.add(label);
    cells.forEach(cell -> line.add(value(cell)));
    if (total != null) {
      line.add(value(total));
    }
    return line;
  }

  /** The row axis's dimensions, a nested pair joined as its raw labels are. */
  private static String rowHeader(ReportSpec spec) {
    return rowDimensions(spec).stream()
        .map(ReportSettingsView::dimensionLabel)
        .collect(Collectors.joining(" / "));
  }

  /** A Date column by its ISO bucket, the measure named when there is more than one. */
  private static String columnHeader(ReportSpec spec, AxisNode column, int index) {
    if (spec.columns().isEmpty() || spec.columns().get(0) != Dimension.DATE) {
      return column.label();
    }
    List<Measure> measures = spec.measures();
    if (measures.size() == 1) {
      return isoDate(column.key());
    }
    String key = column.key();
    Measure measure = measures.get(index % measures.size());
    return isoDate(key.substring(0, key.lastIndexOf('|')))
        + " — "
        + ReportGridBuilder.measureLabel(measure);
  }

  /** A Date row by its ISO bucket; any other row by its label, indented by its depth. */
  private static String rowLabel(ReportSpec spec, AxisNode row) {
    List<Dimension> rows = rowDimensions(spec);
    if (!rows.isEmpty() && rows.get(0) == Dimension.DATE) {
      return isoDate(row.key());
    }
    return INDENT.repeat(row.depth()) + row.label();
  }

  /** The row axis's dimensions — a chart's series fills the row slot (§10). */
  private static List<Dimension> rowDimensions(ReportSpec spec) {
    return spec.rows().isEmpty() ? spec.series() : spec.rows();
  }

  /** A bucket key's own last segment — an expanded day's key is {@code "<bucket>|<day>"}. */
  private static String isoDate(String bucketKey) {
    return bucketKey.substring(bucketKey.lastIndexOf('|') + 1);
  }

  private static String value(Cell cell) {
    if (cell instanceof Cell.Value money) {
      return MoneyFactory.of(money.amount(), money.currencyCode()).getAmount().toPlainString();
    }
    if (cell instanceof Cell.Count count) {
      return String.valueOf(count.count());
    }
    return cell instanceof Cell.Illegal ? "—" : "";
  }

  /** One field, quoted when it carries a separator, a quote or a line break (RFC 4180). */
  private static String field(String text) {
    if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }
}
