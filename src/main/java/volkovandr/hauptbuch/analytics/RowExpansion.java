package volkovandr.hauptbuch.analytics;

/**
 * A Report's row-tree initial state (reporting.md §9.2): {@link #AUTO} (the default), {@link
 * #COLLAPSED} or {@link #EXPANDED}, applied uniformly to every top-level node of the axis that
 * nests a second dimension. Stage e1 takes this as an explicit parameter to {@link
 * ReportEngine#render(ReportSpec, java.time.LocalDate, RowExpansion)}; remembering it against a
 * saved Report, and letting a hand-expanded node override it, is stage e2's job (§9.1).
 */
enum RowExpansion {
  AUTO,
  COLLAPSED,
  EXPANDED
}
