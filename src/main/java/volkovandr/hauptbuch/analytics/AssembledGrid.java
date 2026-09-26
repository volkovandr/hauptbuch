package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Map;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Everything {@link ReportGridBuilder#build} turns into a {@link ReportGrid}: the axis nodes
 * (before suppression) and the raw data behind them, as {@link ReportEngine} assembled them. Kept,
 * rather than thrown away once the grid is built, when a drill-down (reporting.md §12) needs to
 * know which raw groups each figure summed.
 *
 * @param axes the spec's resolved axis shape
 * @param rowNodes every row node, before suppression
 * @param columnBucketNodes every column bucket node, before suppression and measure layout
 * @param candidatesByKey the non-Date dimension's top-level candidates
 * @param data the raw turnover/closing-balance groups
 * @param baseCurrency the book's base currency
 * @param resolved the resolved date range
 */
record AssembledGrid(
    AxisPlan axes,
    List<AxisNode> rowNodes,
    List<AxisNode> columnBucketNodes,
    Map<String, TopLevelNode> candidatesByKey,
    GridData data,
    String baseCurrency,
    RangeResolver.ResolvedRange resolved) {

  ReportGrid build(ReportGridBuilder gridBuilder, ReportSpec spec) {
    return gridBuilder.build(
        spec, axes, rowNodes, columnBucketNodes, candidatesByKey, data, baseCurrency, resolved);
  }

  /** {@code grid}, the grid this assembled into, together with what it was built from. */
  DrillSource drillSource(ReportGrid grid, ReportSpec spec) {
    return new DrillSource(
        grid,
        rowNodes,
        columnBucketNodes,
        new CellValuation.CellContext(axes, candidatesByKey, data, baseCurrency, spec.scope()));
  }
}
