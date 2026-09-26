package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import volkovandr.hauptbuch.analytics.repository.PostingValue;

/**
 * One figure's drill-down (reporting.md §12): the figure itself and the postings behind it, each
 * with the running column accumulated up to and including it — so the last row ends on the figure.
 * A closing balance's list starts from an opening-balance line rather than from zero, and lists
 * only the postings inside the cell's period.
 *
 * @param rowLabel the figure's row, or "Total" for a column total or the grand total
 * @param columnLabel the figure's column, or "Total" for a row total or the grand total
 * @param measure the measure the figure and the running column count
 * @param cell the figure as the Report shows it
 * @param opening a closing balance's opening-balance line; {@code null} for a turnover or count, or
 *     for a figure that opens nothing
 * @param rows the postings in {@code (date, transaction, posting)} order; a posting that sits in
 *     two of a total's addends is listed once per addend, as the total counts it
 */
record DrillDown(
    String rowLabel,
    String columnLabel,
    Measure measure,
    Cell cell,
    Opening opening,
    List<Row> rows) {

  /** Defensively copies the rows. */
  DrillDown {
    rows = List.copyOf(rows);
  }

  /** A list with no opening line — a turnover or count, or a figure that opens nothing. */
  DrillDown(String rowLabel, String columnLabel, Measure measure, Cell cell, List<Row> rows) {
    this(rowLabel, columnLabel, measure, cell, null, rows);
  }

  /** {@code entries}' postings as rows, each with its figure from {@code running}. */
  static List<Row> rows(List<RunningColumn.Entry> entries, List<Cell> running) {
    return IntStream.range(0, entries.size())
        .mapToObj(i -> new Row(entries.get(i).posting(), running.get(i)))
        .toList();
  }

  /** Where the running column ends: its last row, or the opening line when no posting follows. */
  Cell closing() {
    if (!rows.isEmpty()) {
      return rows.getLast().running();
    }
    return opening == null ? Cell.BLANK : opening.running();
  }

  /** The listed postings' ids, each once. */
  List<Long> postingIds() {
    return rows.stream().map(row -> row.posting().postingId()).distinct().toList();
  }

  /**
   * Whether {@code figure} stands for a posting set a list can show. Not a blank cell (no
   * postings), and not a figure that is {@code —} for a structural reason — overlapping tags,
   * different measures or moments added together have no one posting set. A {@code —} for a missing
   * rate or a second currency does: its postings are real, and the list shows which one breaks the
   * sum.
   */
  static boolean isDrillable(Cell figure) {
    if (figure instanceof Cell.Blank) {
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

  /**
   * A closing balance's opening-balance line: what the figure's accounts held before its period,
   * valued as the figure is.
   *
   * @param date the period's first day
   * @param running the line's figure, which the rows below add to
   */
  record Opening(LocalDate date, Cell running) {}
}
