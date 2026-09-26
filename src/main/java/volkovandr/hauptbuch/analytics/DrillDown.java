package volkovandr.hauptbuch.analytics;

import java.util.List;
import volkovandr.hauptbuch.analytics.repository.PostingValue;

/**
 * One figure's drill-down (reporting.md §12): the figure itself and the postings behind it, each
 * with the running column accumulated up to and including it — so the last row ends on the figure.
 *
 * @param rowLabel the figure's row, or "Total" for a column total or the grand total
 * @param columnLabel the figure's column, or "Total" for a row total or the grand total
 * @param measure the measure the figure and the running column count
 * @param cell the figure as the Report shows it
 * @param rows the postings in {@code (date, transaction, posting)} order; a posting that sits in
 *     two of a total's addends is listed once per addend, as the total counts it
 */
record DrillDown(String rowLabel, String columnLabel, Measure measure, Cell cell, List<Row> rows) {

  /** Defensively copies the rows. */
  DrillDown {
    rows = List.copyOf(rows);
  }

  /** The listed postings' ids, each once. */
  List<Long> postingIds() {
    return rows.stream().map(row -> row.posting().postingId()).distinct().toList();
  }

  /**
   * Whether {@code figure} stands for a posting set a list can show. Not a blank cell (no
   * postings), not a closing balance (work package f2), and not a figure that is {@code —} for a
   * structural reason — overlapping tags, different measures or moments added together have no one
   * posting set. A {@code —} for a missing rate or a second currency does: its postings are real,
   * and the list shows which one breaks the sum.
   */
  static boolean isDrillable(Cell figure, Measure measure) {
    if (figure instanceof Cell.Blank || measure.kind() == MeasureKind.CLOSING_BALANCE) {
      return false;
    }
    if (figure instanceof Cell.Illegal illegal) {
      return illegal.reason() == Cell.Reason.MISSING_RATE
          || illegal.reason() == Cell.Reason.MULTI_CURRENCY;
    }
    return true;
  }

  /**
   * One listed posting.
   *
   * @param posting the posting and its value
   * @param running the figure's measure over this row and every row above it
   */
  record Row(PostingValue posting, Cell running) {}
}
