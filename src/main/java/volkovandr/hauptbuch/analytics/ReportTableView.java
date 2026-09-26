package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The table renderer's display-ready model (reporting.md §10's {@code Table} row): every {@link
 * Cell} already formatted as German-style display text ({@link ReportTableViewAssembler}), so the
 * template does no formatting of its own.
 *
 * @param scopeLine the muted header line (§6.4)
 * @param refusalMessage why the grid is empty — {@link ReportGrid#refusalMessage()}; {@code null}
 *     when the Report renders normally
 * @param columns the rendered column headers, with the hierarchy each one sits in
 * @param rows one row per surviving (post-suppression) {@link AxisNode}
 * @param columnTotals the bottom totals row; empty when not shown
 * @param grandTotal blank when either totals axis is off
 * @param drillParams the Report's spec and expansion as query parameters, carried once as the
 *     drill-down form's hidden fields (reporting.md §12) — each figure's button adds only its own
 *     {@code cell}; {@code null} when the table offers no drill-down (a Frame previewed inside the
 *     layout editor's own form, which cannot hold another)
 * @param togglePersists whether the rows' expand/collapse controls post to a saved Report's own
 *     persisting endpoint rather than re-GET the page with an ephemeral expansion ({@link
 *     RowToggle}, issue 02); meaningless when no row has a {@link RowView#toggleUrl}
 */
public record ReportTableView(
    String title,
    String scopeLine,
    String refusalMessage,
    LocalDate resolvedStart,
    LocalDate resolvedEnd,
    List<ColumnView> columns,
    List<RowView> rows,
    boolean showRowTotals,
    boolean showColumnTotals,
    List<CellText> columnTotals,
    CellText grandTotal,
    Map<String, List<String>> drillParams,
    boolean togglePersists) {

  /** Defensively copies the lists to immutable ones. */
  public ReportTableView {
    drillParams = drillParams == null ? null : Map.copyOf(withImmutableValues(drillParams));
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
    columnTotals = List.copyOf(columnTotals);
  }

  /**
   * One rendered column header (stage e5). Column-axis expansion is not in v1 (§9.1), but a column
   * can still sit in a hierarchy — {@code auto} (§9.2) expands a filtered node on either axis — so
   * the header says where it sits.
   *
   * @param depth 0 for a top-level column, otherwise how many levels it is nested
   * @param parent whether this column is an expanded parent: a subtotal over the columns right
   *     after it
   */
  public record ColumnView(String label, int depth, boolean parent) {}

  /**
   * One rendered row: its label, its formatted cells, and its row total (blank when not shown).
   *
   * @param key the row's own {@link AxisNode#key}
   * @param depth 0 for a top-level row, 1 for a nested child (plan stage e), for the template's own
   *     indentation
   * @param expandable whether this row has a second dimension nested beneath it (§9.1)
   * @param expanded whether an {@link #expandable} row is currently showing its children
   * @param toggleUrl the URL this row's expand/collapse control requests ({@link
   *     RowToggle#urlFor}); {@code null} for a row that cannot expand, or a table with no toggle (a
   *     Frame's compact card), which draws the triangle without a control
   */
  public record RowView(
      String key,
      String label,
      int depth,
      boolean expandable,
      boolean expanded,
      String toggleUrl,
      List<CellText> cells,
      CellText rowTotal) {

    /** Defensively copies {@code cells} to an immutable list. */
    public RowView {
      cells = List.copyOf(cells);
    }

    RowView withoutDrillDown() {
      return new RowView(
          key,
          label,
          depth,
          expandable,
          expanded,
          toggleUrl,
          cells.stream().map(CellText::withoutDrillDown).toList(),
          rowTotal.withoutDrillDown());
    }
  }

  private static Map<String, List<String>> withImmutableValues(Map<String, List<String>> params) {
    Map<String, List<String>> copy = new HashMap<>();
    params.forEach((key, values) -> copy.put(key, List.copyOf(values)));
    return copy;
  }

  /** This table without its drill-down — for a Frame previewed inside another form. */
  public ReportTableView withoutDrillDown() {
    return new ReportTableView(
        title,
        scopeLine,
        refusalMessage,
        resolvedStart,
        resolvedEnd,
        columns,
        rows.stream().map(RowView::withoutDrillDown).toList(),
        showRowTotals,
        showColumnTotals,
        columnTotals.stream().map(CellText::withoutDrillDown).toList(),
        grandTotal.withoutDrillDown(),
        null,
        togglePersists);
  }

  /**
   * One formatted cell or total (§10): already-display-ready {@code text}, plus {@code help} — the
   * §11a.7 help-marker text naming which of §7.2's reasons made it {@code —} — {@code null} for a
   * blank cell or a real figure, which need no explaining — and {@code drill}, the figure's {@link
   * CellAddress#token} when it opens a drill-down (reporting.md §12), else {@code null}.
   */
  public record CellText(String text, String help, String drill) {

    /** A figure that opens no drill-down. */
    public CellText(String text, String help) {
      this(text, help, null);
    }

    CellText withDrill(String token) {
      return new CellText(text, help, token);
    }

    CellText withoutDrillDown() {
      return withDrill(null);
    }
  }
}
