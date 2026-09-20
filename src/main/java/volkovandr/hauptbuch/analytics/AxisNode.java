package volkovandr.hauptbuch.analytics;

/**
 * One label on a grid axis (a row or a column) — the dimension value's stable key (an account id, a
 * currency code, a month bucket key, …) and its display label.
 *
 * @param key stable identifier, used to correlate query rows to grid cells
 * @param label display text
 */
public record AxisNode(String key, String label) {

  /**
   * The sentinel key for an axis with no dimension at all (a plain total) — one shared constant so
   * {@link ReportGridBuilder} (which mints the node) and {@link CellValuation} (which reads it back
   * to find that node's raw data) can't drift apart on its spelling.
   */
  static final String TOTAL_KEY = "total";
}
