package volkovandr.hauptbuch.analytics;

/**
 * One label on a grid axis (a row or a column) — the dimension value's stable key (an account id, a
 * currency code, a month bucket key, …) and its display label.
 *
 * <p>Stage e's cross-dimension nesting (reporting.md §3, §9.2) flattens the row tree into one
 * order-preserving list — the currently-visible "frontier" given an expansion state — rather than a
 * real tree structure, so {@link ReportGridBuilder#build} and {@link CellValuation} keep treating
 * cells as a parallel array to rows. {@link #depth} and {@link #parentKey} describe that flattened
 * tree; a depth-1 (nested) node's {@link #key} is {@code "<outerKey>|<innerKey>"} — unique even
 * when the same inner value (e.g. the "Food" category) appears under two different outer nodes,
 * which a bare inner key would not be.
 *
 * @param key stable identifier, used to correlate query rows to grid cells
 * @param label display text
 * @param depth 0 for a top-level node, 1 for a nested child revealed by expanding its parent (stage
 *     e caps nesting at one dimension per axis beyond the top level)
 * @param expandable whether this node has a second dimension nested beneath it (stage e, §3) —
 *     never true below depth 0 in e1, which does not yet expand a depth-1 node further
 * @param parentKey the parent node's {@link #key}, or {@code null} for a depth-0 node
 */
public record AxisNode(String key, String label, int depth, boolean expandable, String parentKey) {

  /**
   * The sentinel key for an axis with no dimension at all (a plain total) — one shared constant so
   * {@link ReportGridBuilder} (which mints the node) and {@link CellValuation} (which reads it back
   * to find that node's raw data) can't drift apart on its spelling.
   */
  static final String TOTAL_KEY = "total";

  /** A depth-0, non-expandable, parentless node — every axis node before stage e. */
  public AxisNode(String key, String label) {
    this(key, label, 0, false, null);
  }
}
