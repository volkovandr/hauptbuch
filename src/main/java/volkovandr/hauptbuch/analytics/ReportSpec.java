package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * A Report's specification (reporting.md §1–§2): dimensions on rows/columns/series, measures,
 * scope, filters, a date range, and the totals/suppression toggles. Not the output — the same spec
 * re-run tomorrow shows different figures (CONTEXT.md "Report").
 *
 * <p>Stage a caps rows and columns at one dimension each (reporting.md §3's two-dimensions-per-axis
 * nesting lands in stage e); a hierarchical dimension renders fully collapsed — one row per
 * top-level node, each summing its whole subtree — since expansion is stage e.
 *
 * <p>Stage b adds {@code series} (§3: the chart legend / bar-group axis, 0–1 dimension). A spec may
 * carry {@code rows} <strong>or</strong> {@code series}, never both — combining a real small-
 * multiples row dimension with a series legend at the same time needs a three-axis grid the engine
 * does not build yet, so it is refused here rather than silently dropping one. When {@code rows} is
 * empty, {@code series}' dimension (if any) fills the same engine slot {@code rows} would have —
 * {@link ReportEngine} and {@link ReportGridBuilder} need no series-specific logic, and the chart
 * renderer alone decides whether {@link ReportGrid#rows()} means "one small chart per row" or "one
 * legend entry per row" by asking which of the two the spec actually set.
 *
 * @param rows 0 or 1 row dimension
 * @param columns 0 or 1 column dimension
 * @param series 0 or 1 series dimension — chart-only, unused by the table renderer (§3)
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
    List<Dimension> series,
    List<Measure> measures,
    Scope scope,
    List<ReportFilter> filters,
    DateRange range,
    boolean rowTotals,
    boolean columnTotals,
    boolean suppressEmptyRows) {

  /** Defensively copies the lists and enforces stage a/b's axis caps. */
  public ReportSpec {
    rows = List.copyOf(rows);
    columns = List.copyOf(columns);
    series = List.copyOf(series);
    measures = List.copyOf(measures);
    filters = List.copyOf(filters);
    if (rows.size() > 1) {
      throw new IllegalArgumentException("Stage a allows at most one row dimension.");
    }
    if (columns.size() > 1) {
      throw new IllegalArgumentException("Stage a allows at most one column dimension.");
    }
    if (series.size() > 1) {
      throw new IllegalArgumentException("A report allows at most one series dimension.");
    }
    if (!rows.isEmpty() && !series.isEmpty()) {
      throw new IllegalArgumentException(
          "A report cannot carry both a row dimension and a series dimension (stage b).");
    }
    if (measures.isEmpty()) {
      throw new IllegalArgumentException("A report needs at least one measure.");
    }
  }
}
