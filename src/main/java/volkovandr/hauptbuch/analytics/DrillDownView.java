package volkovandr.hauptbuch.analytics;

import java.util.List;
import volkovandr.hauptbuch.ledger.RegisterRowView;

/**
 * A drill-down page's display-ready model (reporting.md §12): the figure it opens and the postings
 * behind it as register rows, each row's Balance column carrying the running figure instead of an
 * account balance.
 *
 * @param heading the figure's row and column, e.g. {@code Food · Jan 2026}
 * @param measureLabel what the figure measures
 * @param figure the figure as the Report shows it
 * @param opening a closing balance's opening-balance line above the rows; {@code null} otherwise
 * @param rows the postings, oldest first, the last one's running figure equal to {@code figure}
 * @param backUrl where "Back to the report" leads
 */
record DrillDownView(
    String heading,
    String measureLabel,
    ReportTableView.CellText figure,
    OpeningLine opening,
    List<RegisterRowView> rows,
    String backUrl) {

  /** Defensively copies the rows. */
  DrillDownView {
    rows = List.copyOf(rows);
  }

  /**
   * A closing balance's opening-balance line, display-ready.
   *
   * @param date the period's first day, ISO like the register's own dates
   * @param running what the figure's accounts held before it, as the Balance column shows it
   * @param negative whether that is negative (rendered red, like a register balance)
   */
  record OpeningLine(String date, String running, boolean negative) {}
}
