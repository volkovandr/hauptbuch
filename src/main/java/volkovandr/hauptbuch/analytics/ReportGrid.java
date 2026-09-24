package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * The engine's output (reporting.md §1): a two-dimensional array of {@link Cell}s plus the axis
 * labels that name it. {@link #columns} already has the measures laid out innermost (§3.4) — one
 * rendered column per (column-dimension bucket × measure) pair, so a table renderer needs no
 * further expansion.
 *
 * @param rows the row axis labels, after empty-row suppression (§7.3)
 * @param columns the rendered columns (dimension buckets × measures, measures innermost)
 * @param cells {@code cells.get(rowIndex).get(columnIndex)}
 * @param rowTotals one total per row (right-hand totals column), summing across columns; empty when
 *     {@link ReportSpec#rowTotals()} is off
 * @param columnTotals one total per column (bottom totals row), summing across rows; empty when
 *     {@link ReportSpec#columnTotals()} is off
 * @param grandTotal the single cell where both totals meet; {@link Cell.Blank} when either totals
 *     axis is off
 * @param resolvedStart the range's resolved start (§8.1), for the header line
 * @param resolvedEnd the range's resolved end (§8.1), for the header line
 * @param refusalMessage why the Report shows nothing, computed once here for every renderer to
 *     read: the "scope misses the dimension" message (§6.1, {@link ScopeDimensionMismatch}), or a
 *     spec the engine refuses outright (an empty grid, {@link ReportEngine}); {@code null} when
 *     neither applies
 */
public record ReportGrid(
    List<AxisNode> rows,
    List<AxisNode> columns,
    List<List<Cell>> cells,
    List<Cell> rowTotals,
    List<Cell> columnTotals,
    Cell grandTotal,
    LocalDate resolvedStart,
    LocalDate resolvedEnd,
    String refusalMessage) {

  /** Defensively copies the lists to immutable ones. */
  public ReportGrid {
    rows = List.copyOf(rows);
    columns = List.copyOf(columns);
    cells = List.copyOf(cells.stream().map(List::copyOf).toList());
    rowTotals = List.copyOf(rowTotals);
    columnTotals = List.copyOf(columnTotals);
  }
}
