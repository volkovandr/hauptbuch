package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
  private static final String CURRENCY = "Currency";

  private ReportCsv() {}

  /**
   * {@code grid}, rendered from {@code spec}, as CSV text with CRLF line ends. With a native
   * measure the figures' currency travels alongside them: a Currency column beside each row's
   * label, or — when the columns carry the dimension — a Currency row beneath the header, holding
   * the one currency of that row's or column's native figures, empty when it has none, and {@code
   * —} when they span more than one.
   */
  static String write(ReportSpec spec, ReportGrid grid) {
    if (grid.refusalMessage() != null && grid.rows().isEmpty()) {
      return field(grid.refusalMessage()) + LINE_END;
    }
    Layout layout = new Layout(spec, !grid.rowTotals().isEmpty());
    List<List<String>> lines = new ArrayList<>();
    lines.add(layout.header(grid));
    if (layout.hasCurrencyRow()) {
      lines.add(layout.currencyRow(grid));
    }
    IntStream.range(0, grid.rows().size())
        .mapToObj(
            row ->
                layout.line(
                    rowLabel(spec, grid.rows().get(row)),
                    grid.cells().get(row),
                    layout.rowTotals() ? grid.rowTotals().get(row) : null))
        .forEach(lines::add);
    if (!grid.columnTotals().isEmpty()) {
      lines.add(
          layout.line(TOTAL, grid.columnTotals(), layout.rowTotals() ? grid.grandTotal() : null));
    }
    return lines.stream()
        .map(line -> line.stream().map(ReportCsv::field).collect(Collectors.joining(",")))
        .collect(Collectors.joining(LINE_END, "", LINE_END));
  }

  /**
   * Where a grid's figures go in the file.
   *
   * @param spec the Report
   * @param rowTotals whether the grid shows a totals column
   */
  private record Layout(ReportSpec spec, boolean rowTotals) {

    private boolean anyNative() {
      return spec.measures().stream().anyMatch(m -> m.currency() == PresentationCurrency.ACCOUNT);
    }

    /** Whether the columns carry the non-Date dimension, so its currencies run along a row. */
    boolean hasCurrencyRow() {
      return anyNative() && !spec.columns().isEmpty() && spec.columns().get(0) != Dimension.DATE;
    }

    private boolean currencyColumn() {
      return anyNative() && !hasCurrencyRow();
    }

    /** Whether rendered column {@code index} — or the totals column, after the last — is native. */
    private boolean isNative(int index) {
      List<Measure> measures = spec.measures();
      return measures.get(index % measures.size()).currency() == PresentationCurrency.ACCOUNT;
    }

    List<String> header(ReportGrid grid) {
      List<String> header = new ArrayList<>();
      header.add(rowHeader(spec));
      if (currencyColumn()) {
        header.add(CURRENCY);
      }
      List<AxisNode> columns = grid.columns();
      IntStream.range(0, columns.size())
          .forEach(i -> header.add(columnHeader(spec, columns.get(i), i)));
      if (rowTotals) {
        header.add(TOTAL);
      }
      return header;
    }

    /** Each native column's currency, down its figures and its total. */
    List<String> currencyRow(ReportGrid grid) {
      List<String> line = new ArrayList<>();
      line.add(CURRENCY);
      IntStream.range(0, grid.columns().size())
          .forEach(
              column ->
                  line.add(
                      isNative(column)
                          ? currencyOf(
                              Stream.concat(
                                  grid.cells().stream().map(cells -> cells.get(column)),
                                  grid.columnTotals().stream().skip(column).limit(1)))
                          : ""));
      if (rowTotals) {
        line.add(
            isNative(0)
                ? currencyOf(Stream.concat(grid.rowTotals().stream(), Stream.of(grid.grandTotal())))
                : "");
      }
      return line;
    }

    /** One line: its label, its currency, its figures, and its total when shown ({@code null}). */
    List<String> line(String label, List<Cell> cells, Cell total) {
      List<String> line = new ArrayList<>();
      line.add(label);
      if (currencyColumn()) {
        line.add(
            currencyOf(
                Stream.concat(
                    IntStream.range(0, cells.size()).filter(this::isNative).mapToObj(cells::get),
                    total != null && isNative(0) ? Stream.of(total) : Stream.empty())));
      }
      cells.forEach(cell -> line.add(value(cell)));
      if (total != null) {
        line.add(value(total));
      }
      return line;
    }
  }

  /** The one currency of {@code figures}' values; empty for none, {@code —} for several. */
  private static String currencyOf(Stream<Cell> figures) {
    List<String> currencies =
        figures
            .filter(cell -> cell instanceof Cell.Value)
            .map(cell -> ((Cell.Value) cell).currencyCode())
            .distinct()
            .toList();
    if (currencies.size() > 1) {
      return "—";
    }
    return currencies.isEmpty() ? "" : currencies.get(0);
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
