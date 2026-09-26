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
 * @param rows the postings, oldest first, the last one's running figure equal to {@code figure}
 * @param backUrl where "Back to the report" leads
 */
record DrillDownView(
    String heading,
    String measureLabel,
    ReportTableView.CellText figure,
    List<RegisterRowView> rows,
    String backUrl) {

  /** Defensively copies the rows. */
  DrillDownView {
    rows = List.copyOf(rows);
  }
}
