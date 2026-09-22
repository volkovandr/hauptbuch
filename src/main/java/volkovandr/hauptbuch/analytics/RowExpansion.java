package volkovandr.hauptbuch.analytics;

/**
 * A Report's row-tree initial state (reporting.md §9.2): {@link #AUTO} (the default), {@link
 * #COLLAPSED} or {@link #EXPANDED}, applied uniformly to every top-level node of the axis that
 * nests a second dimension — {@link ReportEngine#render(ReportSpec, java.time.LocalDate,
 * RowExpansion)}. A saved Report's own remembered, hand-toggled per-node state (§9.1) is a {@code
 * Set<String>} of expanded keys instead (plan stage e2, {@link ReportEngine#render(ReportSpec,
 * java.time.LocalDate, java.util.Set)}), since {@code auto}'s uniform rule cannot express "this one
 * node, not that one."
 */
enum RowExpansion {
  AUTO,
  COLLAPSED,
  EXPANDED
}
