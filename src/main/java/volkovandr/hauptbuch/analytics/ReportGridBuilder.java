package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Turns {@link ReportEngine}'s fetched raw data into a {@link ReportGrid}: axis assembly, row
 * suppression (§7.3) and totals (§7.1). Split from {@link ReportEngine} (which owns talking to the
 * repository and settings); per-cell valuation is {@link CellValuation}'s own job.
 */
// CouplingBetweenObjects: this class's whole job is turning every axis/spec vocabulary type
// (Cell, AxisNode, AxisPlan, GridData, TopLevelNode, Measure, MeasureKind, ReportSpec, ...) into a
// ReportGrid — a mapping step, not a service with many behavioural collaborators. Per-cell
// valuation is already split out to CellValuation, and total-legality branching to TotalReason;
// what remains still touches this many small record types because it describes one grid.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
class ReportGridBuilder {

  private final CellValuation cellValuation;

  ReportGridBuilder(CellValuation cellValuation) {
    this.cellValuation = cellValuation;
  }

  /**
   * The axis labels for one axis: {@code axisDim}'s candidates, the Date buckets, or one implicit
   * row/column.
   */
  List<AxisNode> axisNodes(
      Dimension axisDim, Map<String, TopLevelNode> candidatesByKey, List<MonthBucket> buckets) {
    if (axisDim == null) {
      return List.of(new AxisNode(AxisNode.TOTAL_KEY, "Total"));
    }
    if (axisDim == Dimension.DATE) {
      return buckets.stream().map(b -> new AxisNode(b.key(), b.label())).toList();
    }
    return candidatesByKey.values().stream().map(n -> new AxisNode(n.key(), n.label())).toList();
  }

  /** Assemble the full grid: cells, row suppression, and both totals. */
  ReportGrid build(
      ReportSpec spec,
      AxisPlan axes,
      List<AxisNode> rowNodes,
      List<AxisNode> columnBucketNodes,
      Map<String, TopLevelNode> candidatesByKey,
      GridData data,
      String baseCurrency,
      RangeResolver.ResolvedRange resolved) {
    CellValuation.CellContext context =
        new CellValuation.CellContext(axes, candidatesByKey, data, baseCurrency, spec.scope());
    List<List<Cell>> cells = buildCells(spec, rowNodes, columnBucketNodes, context);
    Suppressed suppressed = suppressBlankRows(spec, rowNodes, cells);

    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    boolean rowTotalsForbiddenByTag = axes.colDim() == Dimension.TAG;
    boolean columnTotalsForbiddenByTag = axes.rowDim() == Dimension.TAG;

    List<Cell> rowTotals =
        computeRowTotals(
            spec, suppressed.cells(), rowTotalsForbiddenByTag, axes, anyClosingBalance);
    List<AxisNode> columns = renderedColumns(spec.measures(), axes.colDim(), columnBucketNodes);
    List<Cell> columnTotals =
        computeColumnTotals(
            spec, suppressed.cells(), columns.size(), columnTotalsForbiddenByTag, axes);
    Cell grandTotal =
        computeGrandTotal(
            spec,
            rowTotals,
            rowTotalsForbiddenByTag,
            columnTotalsForbiddenByTag,
            axes,
            anyClosingBalance);

    return new ReportGrid(
        suppressed.rows(),
        columns,
        suppressed.cells(),
        rowTotals,
        columnTotals,
        grandTotal,
        resolved.start(),
        resolved.end(),
        ScopeDimensionMismatch.check(axes.nonDateDim(), spec.scope()));
  }

  private List<List<Cell>> buildCells(
      ReportSpec spec,
      List<AxisNode> rowNodes,
      List<AxisNode> columnBucketNodes,
      CellValuation.CellContext context) {
    return rowNodes.stream()
        .map(rowNode -> buildRowCells(spec, rowNode, columnBucketNodes, context))
        .toList();
  }

  private List<Cell> buildRowCells(
      ReportSpec spec,
      AxisNode rowNode,
      List<AxisNode> columnBucketNodes,
      CellValuation.CellContext context) {
    List<Cell> rowCells = new ArrayList<>();
    for (AxisNode bucketNode : columnBucketNodes) {
      for (Measure measure : spec.measures()) {
        rowCells.add(cellValuation.compute(measure, rowNode, bucketNode, context));
      }
    }
    return rowCells;
  }

  private Suppressed suppressBlankRows(
      ReportSpec spec, List<AxisNode> rowNodes, List<List<Cell>> cells) {
    if (!spec.suppressEmptyRows()) {
      return new Suppressed(rowNodes, cells);
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
    return new Suppressed(rows, kept);
  }

  private List<Cell> computeRowTotals(
      ReportSpec spec,
      List<List<Cell>> cells,
      boolean forbiddenByTag,
      AxisPlan axes,
      boolean anyClosingBalance) {
    if (!spec.rowTotals()) {
      return List.of();
    }
    Cell.Reason forbidden = TotalReason.forRowTotal(spec, axes, forbiddenByTag, anyClosingBalance);
    return cells.stream().map(row -> sumCells(row, forbidden)).toList();
  }

  private List<Cell> computeColumnTotals(
      ReportSpec spec,
      List<List<Cell>> cells,
      int columnCount,
      boolean forbiddenByTag,
      AxisPlan axes) {
    if (!spec.columnTotals()) {
      return List.of();
    }
    List<Cell> columnTotals = new ArrayList<>();
    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
      MeasureKind columnMeasureKind = measureForColumn(spec.measures(), columnIndex).kind();
      Cell.Reason forbidden = TotalReason.forColumnTotal(forbiddenByTag, axes, columnMeasureKind);
      int finalColumnIndex = columnIndex;
      List<Cell> column = cells.stream().map(row -> row.get(finalColumnIndex)).toList();
      columnTotals.add(sumCells(column, forbidden));
    }
    return columnTotals;
  }

  private Cell computeGrandTotal(
      ReportSpec spec,
      List<Cell> rowTotals,
      boolean rowTotalsForbiddenByTag,
      boolean columnTotalsForbiddenByTag,
      AxisPlan axes,
      boolean anyClosingBalance) {
    if (!spec.rowTotals() || !spec.columnTotals()) {
      return Cell.BLANK;
    }
    Cell.Reason forbidden =
        TotalReason.forGrandTotal(
            rowTotalsForbiddenByTag, columnTotalsForbiddenByTag, axes, anyClosingBalance);
    return sumCells(rowTotals, forbidden);
  }

  private List<AxisNode> renderedColumns(
      List<Measure> measures, Dimension colDim, List<AxisNode> columnBucketNodes) {
    if (colDim == null) {
      return measures.stream().map(m -> new AxisNode(measureKey(m), measureLabel(m))).toList();
    }
    if (measures.size() == 1) {
      return columnBucketNodes;
    }
    List<AxisNode> columns = new ArrayList<>();
    for (AxisNode bucket : columnBucketNodes) {
      for (Measure measure : measures) {
        columns.add(
            new AxisNode(
                bucket.key() + "|" + measureKey(measure),
                bucket.label() + " — " + measureLabel(measure)));
      }
    }
    return columns;
  }

  private static String measureKey(Measure measure) {
    return measure.kind() + ":" + measure.currency() + ":" + measure.leg();
  }

  private static String measureLabel(Measure measure) {
    String name =
        switch (measure.kind()) {
          case TURNOVER -> "Turnover";
          case CLOSING_BALANCE -> "Closing balance";
          case COUNT_POSTINGS -> "Count of postings";
          case COUNT_TRANSACTIONS -> "Count of transactions";
        };
    return measure.currency() == PresentationCurrency.ACCOUNT ? name + " (native)" : name;
  }

  private Measure measureForColumn(List<Measure> measures, int columnIndex) {
    return measures.size() == 1 ? measures.get(0) : measures.get(columnIndex % measures.size());
  }

  /**
   * Sum a row or column of cells into its total — illegal for {@code forbiddenReason} (the
   * tag/time/multi-measure rules, §7.2), for whichever reason an addend is itself illegal (the
   * first one found — every addend of one total shares the same structural cause), or for spanning
   * more than one currency (the same §5.4 rule a per-cell account-currency measure obeys, now
   * applied to the total). {@code null} means not structurally forbidden.
   */
  private static Cell sumCells(List<Cell> cells, Cell.Reason forbiddenReason) {
    if (forbiddenReason != null) {
      return new Cell.Illegal(forbiddenReason);
    }
    Optional<Cell> illegalAddend =
        cells.stream().filter(c -> c instanceof Cell.Illegal).findFirst();
    if (illegalAddend.isPresent()) {
      return illegalAddend.get();
    }
    if (cells.stream().allMatch(c -> c instanceof Cell.Blank)) {
      return Cell.BLANK;
    }
    if (cells.stream().anyMatch(c -> c instanceof Cell.Count)) {
      long sum =
          cells.stream()
              .filter(c -> c instanceof Cell.Count)
              .mapToLong(c -> ((Cell.Count) c).count())
              .sum();
      return new Cell.Count(sum);
    }
    List<Cell.Value> values =
        cells.stream().filter(c -> c instanceof Cell.Value).map(c -> (Cell.Value) c).toList();
    Set<String> currencies =
        values.stream().map(Cell.Value::currencyCode).collect(Collectors.toSet());
    if (currencies.size() > 1) {
      return new Cell.Illegal(Cell.Reason.MULTI_CURRENCY);
    }
    BigDecimal sum =
        values.stream().map(Cell.Value::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Cell.Value(sum, currencies.iterator().next());
  }

  /** The row axis and cells after {@link ReportSpec#suppressEmptyRows()} is applied. */
  private record Suppressed(List<AxisNode> rows, List<List<Cell>> cells) {}
}
