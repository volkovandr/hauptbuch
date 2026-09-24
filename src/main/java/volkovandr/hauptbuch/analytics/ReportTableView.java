package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;

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
 * @param toggleReportId the saved Report id the row-tree's expand/collapse links post to (plan
 *     stage e2) — {@code null} disables them (a Preset, a Frame's compact card, or a page currently
 *     showing an unsaved draft; reporting.md §9.1's expansion state is remembered only against a
 *     saved Report's own current spec)
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
    Long toggleReportId) {

  /** Defensively copies the lists to immutable ones. */
  public ReportTableView {
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
   * @param key the row's own {@link AxisNode#key} — the toggle link's {@code node} value
   * @param depth 0 for a top-level row, 1 for a nested child (plan stage e), for the template's own
   *     indentation
   * @param expandable whether this row has a second dimension nested beneath it (§9.1) — draws an
   *     expand/collapse control when {@link ReportTableView#toggleReportId} is also non-{@code
   *     null}
   * @param expanded whether an {@link #expandable} row is currently showing its children
   */
  public record RowView(
      String key,
      String label,
      int depth,
      boolean expandable,
      boolean expanded,
      List<CellText> cells,
      CellText rowTotal) {

    /** Defensively copies {@code cells} to an immutable list. */
    public RowView {
      cells = List.copyOf(cells);
    }
  }

  /**
   * One formatted cell or total (§10): already-display-ready {@code text}, plus {@code help} — the
   * §11a.7 help-marker text naming which of §7.2's reasons made it {@code —} — {@code null} for a
   * blank cell or a real figure, which need no explaining.
   */
  public record CellText(String text, String help) {}
}
