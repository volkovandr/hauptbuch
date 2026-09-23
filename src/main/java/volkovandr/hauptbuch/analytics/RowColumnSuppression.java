package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether an all-blank row or column is hidden (reporting.md §7.3, e4 follow-up for the column
 * half). Split out of {@link ReportGridBuilder} purely to keep that class's own cyclomatic
 * complexity down, mirroring {@link TotalReason}'s own split: these two checks share no state with
 * the rest of the grid build.
 */
final class RowColumnSuppression {

  private RowColumnSuppression() {}

  /** The row axis and cells after {@link ReportSpec#suppressEmptyRows()} is applied. */
  record Rows(List<AxisNode> rows, List<List<Cell>> cells) {}

  /** {@link Rows}'s own mirror for {@link ReportSpec#suppressEmptyColumns()}. */
  record Columns(List<AxisNode> columnBucketNodes, List<List<Cell>> cells) {}

  static Rows suppressBlankRows(ReportSpec spec, List<AxisNode> rowNodes, List<List<Cell>> cells) {
    if (!spec.suppressEmptyRows()) {
      return new Rows(rowNodes, cells);
    }
    List<AxisNode> rows = new ArrayList<>();
    List<List<Cell>> kept = new ArrayList<>();
    for (int i = 0; i < cells.size(); i++) {
      boolean allBlank = cells.get(i).stream().allMatch(c -> c instanceof Cell.Blank);
      if (!allBlank) {
        rows.add(rowNodes.get(i));
        kept.add(cells.get(i));
      }
    }
    return new Rows(rows, kept);
  }

  /**
   * {@link #suppressBlankRows}'s own mirror for the column axis: a column whose every cell is blank
   * across every remaining row is hidden — e.g. an account candidate ({@link
   * volkovandr.hauptbuch.analytics.repository.ReportQueryRepository#topLevelAccounts} lists every
   * one regardless of the spec's own filters, precisely so an all-blank one can be suppressed
   * rather than never listed) that a "touching …" filter excludes entirely. A bucket spans {@code
   * measuresPerBucket} consecutive cells in every row (the grid builder's own layout), so a whole
   * bucket is kept or dropped as one unit, never split mid-measure.
   */
  // AvoidInstantiatingObjectsInLoops: one `new ArrayList<>()` per surviving row builds that row's
  // own kept-cell list — bounded by row count, not a per-iteration allocation inside a tight
  // numeric loop the rule means to catch (mirrors ReportDataFetcher.makeEveryListMutable's own
  // suppression for the same shape).
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  static Columns suppressBlankColumns(
      ReportSpec spec,
      List<AxisNode> columnBucketNodes,
      List<List<Cell>> cells,
      int measuresPerBucket) {
    if (!spec.suppressEmptyColumns() || columnBucketNodes.isEmpty()) {
      return new Columns(columnBucketNodes, cells);
    }
    List<AxisNode> keptBuckets = new ArrayList<>();
    List<Integer> keptStarts = new ArrayList<>();
    for (int bucket = 0; bucket < columnBucketNodes.size(); bucket++) {
      int start = bucket * measuresPerBucket;
      int end = start + measuresPerBucket;
      boolean allBlank =
          cells.stream()
              .allMatch(
                  row -> row.subList(start, end).stream().allMatch(c -> c instanceof Cell.Blank));
      if (!allBlank) {
        keptBuckets.add(columnBucketNodes.get(bucket));
        keptStarts.add(start);
      }
    }
    if (keptBuckets.size() == columnBucketNodes.size()) {
      return new Columns(columnBucketNodes, cells);
    }
    List<List<Cell>> kept = new ArrayList<>();
    for (List<Cell> row : cells) {
      List<Cell> keptRow = new ArrayList<>();
      for (int start : keptStarts) {
        keptRow.addAll(row.subList(start, start + measuresPerBucket));
      }
      kept.add(keptRow);
    }
    return new Columns(keptBuckets, kept);
  }
}
