package volkovandr.hauptbuch.analytics.repository;

/**
 * One candidate row of a hierarchical dimension, independent of whether it has any data this period
 * — needed so an all-blank row can still be listed, then suppressed by {@link
 * volkovandr.hauptbuch.analytics.ReportSpec#suppressEmptyRows()} (reporting.md §7.3) rather than
 * simply never appearing.
 *
 * @param key stable identifier
 * @param label display label
 * @param type the backing account's {@code type}, for the credit-natural display flip; {@code null}
 *     for a tag
 * @param hasChildren whether this node has at least one live child of its own (reporting.md §9.1) —
 *     drives {@link volkovandr.hauptbuch.analytics.AxisNode#expandable()} for the same-dimension
 *     "expand one node within its own hierarchy" case, at any depth. Irrelevant (and always {@code
 *     false} via the three-arg constructor) for a dimension that is never nestable ({@link
 *     volkovandr.hauptbuch.analytics.AutoExpansion#isNestable}) or for a synthetic bucket (the tag
 *     tree's {@code (unspecified)} row) that is never itself expandable regardless of what it
 *     counts.
 */
public record TopLevelNode(String key, String label, String type, boolean hasChildren) {

  /** A node with no known children — every non-hierarchical dimension's own candidates. */
  public TopLevelNode(String key, String label, String type) {
    this(key, label, type, false);
  }
}
