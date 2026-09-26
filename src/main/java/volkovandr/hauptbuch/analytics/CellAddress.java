package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * Which figure of a rendered Report a drill-down opens (reporting.md §12): a body cell, a row or
 * column total, or the grand total. Named by keys rather than grid positions, so a link keeps
 * pointing at the same figure while rows come and go with the data.
 *
 * @param rowKey the row's {@link AxisNode#key}; {@code null} for a column total or the grand total
 * @param columnKey the column bucket's {@link AxisNode#key} (the plain-total sentinel when the
 *     columns carry no dimension); {@code null} for a row total or the grand total
 * @param measureIndex which of the spec's measures; 0 for a row total or the grand total, which sum
 *     across every column of the row
 */
record CellAddress(String rowKey, String columnKey, int measureIndex) {

  private static final String SEPARATOR = "/";
  private static final int TOKEN_PARTS = 3;
  private static final String TOTAL_LABEL = "Total";

  /** Rejects a negative measure index. */
  CellAddress {
    if (measureIndex < 0) {
      throw new IllegalArgumentException("A measure index is never negative: " + measureIndex);
    }
  }

  /** The body cell at grid position ({@code row}, {@code column}). */
  static CellAddress forBody(ReportSpec spec, ReportGrid grid, int row, int column) {
    return new CellAddress(
        grid.rows().get(row).key(), bucketKey(spec, grid, column), column % spec.measures().size());
  }

  /** Row {@code row}'s total, summing across the columns. */
  static CellAddress forRowTotal(ReportGrid grid, int row) {
    return new CellAddress(grid.rows().get(row).key(), null, 0);
  }

  /** Column {@code column}'s total, summing down the rows. */
  static CellAddress forColumnTotal(ReportSpec spec, ReportGrid grid, int column) {
    return new CellAddress(null, bucketKey(spec, grid, column), column % spec.measures().size());
  }

  /** The grand total, where both totals meet. */
  static CellAddress forGrandTotal() {
    return new CellAddress(null, null, 0);
  }

  /**
   * This address as one form value, {@code measureIndex/rowKey/columnKey}, a total's missing key
   * left empty — the drill-down button's {@code cell} parameter. No node key contains a {@code /}.
   */
  String token() {
    return measureIndex + SEPARATOR + nullToEmpty(rowKey) + SEPARATOR + nullToEmpty(columnKey);
  }

  /**
   * The address {@link #token} encoded.
   *
   * @throws IllegalArgumentException when {@code token} is not one
   */
  static CellAddress parse(String token) {
    String[] parts = token.split(SEPARATOR, -1);
    if (parts.length != TOKEN_PARTS) {
      throw new IllegalArgumentException("Not a cell address: " + token);
    }
    try {
      return new CellAddress(
          emptyToNull(parts[1]), emptyToNull(parts[2]), Integer.parseInt(parts[0]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Not a cell address: " + token, e);
    }
  }

  private static String nullToEmpty(String key) {
    return key == null ? "" : key;
  }

  private static String emptyToNull(String key) {
    return key.isEmpty() ? null : key;
  }

  /** The figure this address names on {@code grid}; blank when it is not on the grid. */
  Cell figureOn(ReportSpec spec, ReportGrid grid) {
    int row = rowIndex(grid);
    int column = columnIndex(spec, grid);
    if (rowKey != null && columnKey != null) {
      return row < 0 || column < 0 ? Cell.BLANK : grid.cells().get(row).get(column);
    }
    if (rowKey != null) {
      return at(grid.rowTotals(), row);
    }
    return columnKey != null ? at(grid.columnTotals(), column) : grid.grandTotal();
  }

  /** The label of this address's row on {@code grid}, or {@code Total} along the rows. */
  String rowLabelOn(ReportGrid grid) {
    int row = rowIndex(grid);
    return row < 0 ? TOTAL_LABEL : grid.rows().get(row).label();
  }

  /** The label of this address's column on {@code grid}, or {@code Total} along the columns. */
  String columnLabelOn(ReportSpec spec, ReportGrid grid) {
    int column = columnIndex(spec, grid);
    return column < 0 ? TOTAL_LABEL : grid.columns().get(column).label();
  }

  /** A shown total, or blank when the totals are off or the index is not on the grid. */
  private static Cell at(List<Cell> totals, int index) {
    return index < 0 || index >= totals.size() ? Cell.BLANK : totals.get(index);
  }

  private int rowIndex(ReportGrid grid) {
    if (rowKey == null) {
      return -1;
    }
    for (int i = 0; i < grid.rows().size(); i++) {
      if (grid.rows().get(i).key().equals(rowKey)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The rendered column this address falls in — the bucket's column for its measure — or -1 when no
   * such column is on the grid (a total along the columns has none).
   */
  int columnIndex(ReportSpec spec, ReportGrid grid) {
    if (columnKey == null) {
      return -1;
    }
    int measures = spec.measures().size();
    for (int column = measureIndex; column < grid.columns().size(); column += measures) {
      if (columnKey.equals(bucketKey(spec, grid, column))) {
        return column;
      }
    }
    return -1;
  }

  /**
   * The bucket key of rendered column {@code column}: the column's own key with one measure,
   * otherwise that key minus the measure suffix {@link ReportGridBuilder} appends when it lays the
   * measures out innermost (§3.4).
   */
  private static String bucketKey(ReportSpec spec, ReportGrid grid, int column) {
    if (spec.columns().isEmpty()) {
      return AxisNode.TOTAL_KEY;
    }
    String key = grid.columns().get(column).key();
    if (spec.measures().size() == 1) {
      return key;
    }
    String suffix =
        "|" + ReportGridBuilder.measureKey(spec.measures().get(column % spec.measures().size()));
    return key.substring(0, key.length() - suffix.length());
  }
}
