package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * A Report's specification (reporting.md §1–§2): dimensions on rows/columns, measures, scope,
 * filters, a date range, and the totals/suppression toggles. Not the output — the same spec re-run
 * tomorrow shows different figures (CONTEXT.md "Report").
 *
 * <p>Stage a caps rows and columns at one dimension each and offers no series axis (reporting.md
 * §3's nesting and small-multiples land in stage e/b respectively); a hierarchical dimension
 * renders fully collapsed — one row per top-level node, each summing its whole subtree — since
 * expansion is stage e.
 *
 * @param rows 0 or 1 row dimension
 * @param columns 0 or 1 column dimension
 * @param measures the report's columns-of-measures; at least one
 * @param scope which account types/subtrees a flow measure counts (§6.1)
 * @param filters AND-combined filter clauses (§6.2)
 * @param range the date range, as two anchor-grammar endpoints (§8.1)
 * @param rowTotals whether to add a totals column, summing each row across columns (§7.1)
 * @param columnTotals whether to add a totals row, summing each column across rows (§7.1)
 * @param suppressEmptyRows whether an all-blank row is hidden (§7.3); on by default
 */
public record ReportSpec(
    List<Dimension> rows,
    List<Dimension> columns,
    List<Measure> measures,
    Scope scope,
    List<ReportFilter> filters,
    DateRange range,
    boolean rowTotals,
    boolean columnTotals,
    boolean suppressEmptyRows) {

  /** Defensively copies the lists and enforces stage a's one-dimension-per-axis cap. */
  public ReportSpec {
    rows = List.copyOf(rows);
    columns = List.copyOf(columns);
    measures = List.copyOf(measures);
    filters = List.copyOf(filters);
    if (rows.size() > 1) {
      throw new IllegalArgumentException("Stage a allows at most one row dimension.");
    }
    if (columns.size() > 1) {
      throw new IllegalArgumentException("Stage a allows at most one column dimension.");
    }
    if (measures.isEmpty()) {
      throw new IllegalArgumentException("A report needs at least one measure.");
    }
  }
}
