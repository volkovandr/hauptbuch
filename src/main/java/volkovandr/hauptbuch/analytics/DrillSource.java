package volkovandr.hauptbuch.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;

/**
 * A rendered Report together with what it was built from ({@link ReportEngine#drillSource}) — the
 * drill-down's input (reporting.md §12). The axis nodes are the grid's before suppression; a
 * suppressed row or column is blank, so it never contributes a posting.
 *
 * @param grid the grid the page shows
 * @param rowNodes every row node the grid was built over
 * @param columnBucketNodes every column bucket node (before measures are laid out innermost)
 * @param cellContext the raw data, with posting ids; {@code null} for a refused spec
 */
record DrillSource(
    ReportGrid grid,
    List<AxisNode> rowNodes,
    List<AxisNode> columnBucketNodes,
    CellValuation.CellContext cellContext) {

  /** Defensively copies the lists. */
  DrillSource {
    rowNodes = List.copyOf(rowNodes);
    columnBucketNodes = List.copyOf(columnBucketNodes);
  }

  /**
   * The postings behind the turnover or count figure at {@code address}, each with the body cells
   * it sits in: a body cell is one cell; a total is every top-level cell along its row, down its
   * column, or both — the cells {@link ReportGridBuilder} adds up. Each cell's postings are the ids
   * of exactly the raw groups {@link CellValuation} matched for it, flipped as that cell displays.
   */
  Map<Long, List<Membership>> postingsBehind(CellAddress address, Measure measure) {
    List<AxisNode> rows = nodes(rowNodes, address.rowKey());
    List<AxisNode> columns = nodes(columnBucketNodes, address.columnKey());
    List<Addend> addends =
        rows.stream()
            .flatMap(row -> columns.stream().map(column -> new Addend(row, column)))
            .toList();
    return IntStream.range(0, addends.size())
        .boxed()
        .flatMap(i -> memberships(i, addends.get(i), measure))
        .collect(
            Collectors.groupingBy(Membership::postingId, LinkedHashMap::new, Collectors.toList()));
  }

  private Stream<Membership> memberships(int index, Addend addend, Measure measure) {
    List<RawTurnoverCell> matches =
        CellValuation.turnoverMatches(measure, addend.row(), addend.column(), cellContext);
    boolean creditNatural = CellValuation.isCreditNatural(matches, cellContext.scope());
    return matches.stream()
        .flatMap(match -> match.postingIds().stream())
        .map(postingId -> new Membership(postingId, index, creditNatural));
  }

  /** The node keyed {@code key}, or every top-level node when {@code key} is a total's null. */
  private static List<AxisNode> nodes(List<AxisNode> nodes, String key) {
    if (key == null) {
      return nodes.stream().filter(node -> node.depth() == 0).toList();
    }
    return nodes.stream().filter(node -> node.key().equals(key)).toList();
  }

  /** One body cell a figure sums. */
  private record Addend(AxisNode row, AxisNode column) {}

  /**
   * One of a posting's cells.
   *
   * @param postingId the posting
   * @param addend which of the figure's cells — a body cell has one, a total many
   * @param creditNatural whether that cell displays sign-flipped
   */
  record Membership(long postingId, int addend, boolean creditNatural) {}
}
