package volkovandr.hauptbuch.analytics;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A Report's specification (reporting.md §1–§2): dimensions on rows/columns/series, measures,
 * scope, filters, a date range, and the totals/suppression toggles. Not the output — the same spec
 * re-run tomorrow shows different figures (CONTEXT.md "Report").
 *
 * <p>Stage e allows up to two dimensions on rows or columns — nesting, not a cartesian product
 * (§3): {@code rows = [Tag, Category]} renders each tag as a group whose expansion reveals its
 * category breakdown. A single dimension still renders fully collapsed by default — one row per
 * top-level node, each summing its whole subtree — until expanded (§9). The constructor rejects the
 * same dimension repeated on one axis (nesting a dimension under itself has no meaning here — a
 * single hierarchical dimension's own subtree is walked by expanding one node, not by naming it
 * twice) and rejects {@link Dimension#DATE} combined with another dimension on the same axis (the
 * ladder, §8.2, is its own axis-filling mechanism). {@link ReportEngine} keeps stage a's remaining
 * rule: only one of the two axes may carry a non-Date dimension at all — a cross-axis
 * two-different- dimension cartesian (e.g. Category rows × Account columns) is out of scope.
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
 * @param rows 0, 1 or 2 row dimensions (§3 — two is nesting, not a cartesian product)
 * @param columns 0, 1 or 2 column dimensions
 * @param series 0 or 1 series dimension — chart-only, unused by the table renderer (§3)
 * @param measures the report's columns-of-measures; at least one
 * @param scope which account types a flow measure counts (§6.1)
 * @param filters AND-combined filter clauses (§6.2); at most one per {@link FilterField} (§6.2 —
 *     two ANDed filters on the same field, e.g. "touching BankAaa and touching BankBbb", is a
 *     register question, not a reporting one)
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

  /** §3's per-axis cap: rows or columns may nest at most this many dimensions. */
  private static final int MAX_DIMENSIONS_PER_AXIS = 2;

  /** Defensively copies the lists and enforces stage a/b's axis caps. */
  public ReportSpec {
    rows = List.copyOf(rows);
    columns = List.copyOf(columns);
    series = List.copyOf(series);
    measures = List.copyOf(measures);
    filters = List.copyOf(filters);
    requireAxisShape(rows, "rows");
    requireAxisShape(columns, "columns");
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
    Set<FilterField> seenFields = EnumSet.noneOf(FilterField.class);
    for (ReportFilter filter : filters) {
      if (!seenFields.add(filter.field())) {
        throw new IllegalArgumentException(
            "A report carries at most one filter per field (§6.2): " + filter.field());
      }
    }
  }

  /**
   * One axis (rows or columns) allows 0–2 dimensions (§3), never the same dimension twice, and
   * never {@link Dimension#DATE} alongside another dimension — the ladder fills an axis on its own
   * (§8.2).
   */
  private static void requireAxisShape(List<Dimension> dimensions, String axisName) {
    if (dimensions.size() > MAX_DIMENSIONS_PER_AXIS) {
      throw new IllegalArgumentException(
          "A report allows at most two " + axisName + " dimensions (§3).");
    }
    boolean nestsTwo = dimensions.size() == MAX_DIMENSIONS_PER_AXIS;
    if (nestsTwo && dimensions.get(0) == dimensions.get(1)) {
      throw new IllegalArgumentException(
          "A report cannot repeat the same dimension on " + axisName + " (§3).");
    }
    if (nestsTwo && dimensions.contains(Dimension.DATE)) {
      throw new IllegalArgumentException(
          "Date cannot combine with another dimension on " + axisName + " (§8.2).");
    }
  }
}
