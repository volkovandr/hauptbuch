package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * The table renderer's display-ready model (reporting.md §10's {@code Table} row): every {@link
 * Cell} already formatted as German-style display text ({@link ReportTableViewAssembler}), so the
 * template does no formatting of its own.
 *
 * @param scopeLine the muted header line (§6.4)
 * @param scopeMismatch the "scope misses the dimension" message (§6.1, {@link
 *     ScopeDimensionMismatch}); {@code null} when scope and dimension agree
 * @param columnLabels the rendered column headers
 * @param rows one row per surviving (post-suppression) {@link AxisNode}
 * @param columnTotals the bottom totals row; empty when not shown
 * @param grandTotal blank when either totals axis is off
 */
public record ReportTableView(
    String title,
    String scopeLine,
    String scopeMismatch,
    LocalDate resolvedStart,
    LocalDate resolvedEnd,
    List<String> columnLabels,
    List<RowView> rows,
    boolean showRowTotals,
    boolean showColumnTotals,
    List<CellText> columnTotals,
    CellText grandTotal) {

  /** Defensively copies the lists to immutable ones. */
  public ReportTableView {
    columnLabels = List.copyOf(columnLabels);
    rows = List.copyOf(rows);
    columnTotals = List.copyOf(columnTotals);
  }

  /** One rendered row: its label, its formatted cells, and its row total (blank when not shown). */
  public record RowView(String label, List<CellText> cells, CellText rowTotal) {

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
