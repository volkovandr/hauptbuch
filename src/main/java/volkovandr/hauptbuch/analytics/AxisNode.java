package volkovandr.hauptbuch.analytics;

/**
 * One label on a grid axis (a row or a column) — the dimension value's stable key (an account id, a
 * currency code, a month bucket key, …) and its display label.
 *
 * <p>Stage e's nesting (reporting.md §3, §9) flattens the row tree into one order-preserving list —
 * the currently-visible "frontier" given an expansion state — rather than a real tree structure, so
 * {@link ReportGridBuilder#build} and {@link CellValuation} keep treating cells as a parallel array
 * to rows. {@link #depth} and {@link #parentKey} describe that flattened tree; a nested node's
 * {@link #key} is {@code "<parentKey>|<ownKey>"} — unique even when the same value (e.g. the "Food"
 * category) appears under two different parents, which a bare own-key would not be.
 *
 * @param key stable identifier, used to correlate query rows to grid cells
 * @param label display text
 * @param depth 0 for a top-level node, otherwise how many expand steps revealed it. Cross-dimension
 *     nesting (two different dimensions, §3) caps out at depth 1 — {@link ReportSpec} allows at
 *     most two dimensions per axis. Same-dimension nesting (one hierarchical dimension expanding
 *     into its own children, §9.1) recurses to whatever depth the hierarchy itself has.
 * @param expandable whether this node has something to reveal by expanding it: a second, different
 *     dimension nested beneath it (always true at depth 0 when the axis nests two dimensions), or —
 *     for the same-dimension case, at any depth — {@link
 *     volkovandr.hauptbuch.analytics.repository.TopLevelNode#hasChildren} of its own. A
 *     cross-dimension depth-1 (or deeper) node is never expandable — stage e does not nest a third
 *     dimension.
 * @param parentKey the parent node's {@link #key}, or {@code null} for a depth-0 node
 * @param expanded whether an {@link #expandable} node is currently showing its children in the
 *     frontier (stage e2, §9.1/§9.2) — always {@code false} for a non-expandable node
 */
public record AxisNode(
    String key, String label, int depth, boolean expandable, String parentKey, boolean expanded) {

  /**
   * The sentinel key for an axis with no dimension at all (a plain total) — one shared constant so
   * {@link ReportGridBuilder} (which mints the node) and {@link CellValuation} (which reads it back
   * to find that node's raw data) can't drift apart on its spelling.
   */
  static final String TOTAL_KEY = "total";

  /** A depth-0, non-expandable, parentless, collapsed node — every axis node before stage e. */
  public AxisNode(String key, String label) {
    this(key, label, 0, false, null, false);
  }

  /** A depth-0 or depth-1 node not currently expanded — every axis node before stage e2. */
  public AxisNode(String key, String label, int depth, boolean expandable, String parentKey) {
    this(key, label, depth, expandable, parentKey, false);
  }

  /**
   * A (possibly composite, §9.1) frontier key's own real database id — the segment after the last
   * {@code "|"}, or the whole key when it names a depth-0 (top-level) node. Every account/tag id is
   * globally unique (a strict single-parent tree), so this is always enough to seed the next
   * level's "children of this node" query, regardless of how deep {@code key} nests.
   *
   * <p>{@code expandedNodeKeys} is a persisted, hand-edited-by-request set (stage e2's toggle
   * endpoint) — a garbage or stale trailing segment (a malformed request, or a real non-numeric
   * leaf key like Tag's own {@code "<id>:unspecified"} bucket, which is never itself expandable and
   * so never legitimately reaches here as a parent) degrades to {@code -1}, an id no account/tag
   * row ever has, rather than throwing: the same "simply never referenced" graceful handling {@code
   * ReportEngine#expandedOuterKeys} already documents for a stale top-level key, extended to a
   * malformed trailing segment instead of a missing one.
   */
  static long realId(String key) {
    int lastSeparator = key.lastIndexOf('|');
    String segment = lastSeparator < 0 ? key : key.substring(lastSeparator + 1);
    try {
      return Long.parseLong(segment);
    } catch (NumberFormatException malformed) {
      return -1;
    }
  }
}
