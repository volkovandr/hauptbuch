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
 * Turns {@link ReportEngine}'s fetched raw data into a {@link ReportGrid}: axis assembly and totals
 * (§7.1). Split from {@link ReportEngine} (which owns talking to the repository and settings);
 * per-cell valuation is {@link CellValuation}'s own job, row/column suppression (§7.3) is {@link
 * RowColumnSuppression}'s.
 */
// CouplingBetweenObjects: this class's whole job is turning every axis/spec vocabulary type
// (Cell, AxisNode, AxisPlan, GridData, TopLevelNode, Measure, MeasureKind, ReportSpec, ...) into a
// ReportGrid — a mapping step, not a service with many behavioural collaborators. Per-cell
// valuation is already split out to CellValuation, total-legality branching to TotalReason, and
// row/column suppression to RowColumnSuppression; what remains still touches this many small
// record types because it describes one grid.
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
      Dimension axisDim, Map<String, TopLevelNode> candidatesByKey, List<DateBucket> buckets) {
    if (axisDim == null) {
      return List.of(new AxisNode(AxisNode.TOTAL_KEY, "Total"));
    }
    if (axisDim == Dimension.DATE) {
      return buckets.stream().map(b -> new AxisNode(b.key(), b.label())).toList();
    }
    return candidatesByKey.values().stream().map(n -> new AxisNode(n.key(), n.label())).toList();
  }

  /**
   * The axis labels for the axis that carries stage e's nesting (reporting.md §3, §9): a flat,
   * order-preserving list — the currently-visible tree "frontier" given {@code expandedKeys} —
   * rather than a real tree, so {@link #build} needs no further change (cells stay a parallel array
   * to rows). An expanded node's children come from one of two sources, mirroring {@link
   * ReportDataFetcher}'s own split: when {@code innerDim} is set, a depth-0 node's children are
   * {@code innerCandidatesByKey}'s own top-level breakdown, reused unscoped under every expanded
   * outer node (§3's cross-dimension nesting — the scoping happens in the data fetch, not here) and
   * are themselves always terminal (stage e caps cross-dimension nesting at the two dimensions
   * {@link ReportSpec} allows, so a depth-1 cross-dimension child is never itself expandable);
   * otherwise (no second, different dimension at all) {@code sameDimensionChildrenByParentKey}'s
   * entry for that node, recursing to whatever depth {@code expandedKeys} names (§9.1's "expand a
   * node within its own hierarchy" case, e.g. plain {@code rows = [Category]}, which nests as deep
   * as the category tree itself does). Either way a child node's key is {@code
   * "<parentKey>|<childKey>"} (never just the child's own key, which the same "Food" category could
   * repeat under two different expanded tags in the cross-dimension case).
   *
   * @param outerDim the axis's own (outer, or only) dimension, or {@code null} for no dimension
   * @param innerDim the second, different dimension, or {@code null} when the axis nests only
   *     within {@code outerDim}'s own hierarchy (or not at all)
   * @param sameDimensionChildrenByParentKey each expanded node's own direct children (§9.1), keyed
   *     by that node's own (possibly composite, any depth) key — only populated (and only
   *     consulted) when {@code innerDim} is {@code null}
   * @param expandedKeys which nodes are expanded (reporting.md §9.2), at any depth — a node is
   *     {@link AxisNode#expandable} when {@code outerDim} can nest a second dimension at all (§9.1
   *     — a hierarchical dimension only), it is not the per-currency "personal debts" pseudo-bucket
   *     (not a single subtree, {@link AutoExpansion#isPersonLeafBucket}), and — for the same-
   *     dimension case only — it actually {@link TopLevelNode#hasChildren} of its own; a cross-
   *     dimension depth-0 node is always expandable when nestable, since its children come from the
   *     independent inner dimension, not its own subtree
   */
  List<AxisNode> frontierNodes(
      Dimension outerDim,
      Dimension innerDim,
      Map<String, TopLevelNode> outerCandidatesByKey,
      Map<String, TopLevelNode> innerCandidatesByKey,
      Map<String, List<TopLevelNode>> sameDimensionChildrenByParentKey,
      Set<String> expandedKeys) {
    if (outerDim == null) {
      return List.of(new AxisNode(AxisNode.TOTAL_KEY, "Total"));
    }
    List<AxisNode> frontier = new ArrayList<>();
    for (TopLevelNode outer : outerCandidatesByKey.values()) {
      addFrontierNode(
          frontier,
          outer,
          outer.key(),
          0,
          null,
          outerDim,
          innerDim,
          innerCandidatesByKey,
          sameDimensionChildrenByParentKey,
          expandedKeys);
    }
    return frontier;
  }

  /**
   * The Date row axis as an expand-in-place tree (reporting.md §9.1, §15): one depth-0 row per
   * ladder bucket (month or week), each expandable into its own days. A day row's key is {@code
   * "<bucketKey>|<dayKey>"}, matching the composite bucket key {@link GridData#withDays} gives that
   * day's data. The ladder's year rung is not part of the tree yet.
   */
  List<AxisNode> dateFrontierNodes(List<DateBucket> buckets, Set<String> expandedKeys) {
    List<AxisNode> frontier = new ArrayList<>();
    for (DateBucket bucket : buckets) {
      boolean expanded = expandedKeys.contains(bucket.key());
      frontier.add(new AxisNode(bucket.key(), bucket.label(), 0, true, null, expanded));
      if (expanded) {
        for (DateBucket day : bucket.days()) {
          frontier.add(
              new AxisNode(bucket.key() + "|" + day.key(), day.label(), 1, false, bucket.key()));
        }
      }
    }
    return frontier;
  }

  // ExcessiveParameterList: one recursive walk of the frontier tree, carrying the same fixed
  // context (the two dimensions and their two child sources) down every level — splitting it would
  // just wrap this same parameter list in a context object, not reduce it.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  private void addFrontierNode(
      List<AxisNode> frontier,
      TopLevelNode node,
      String key,
      int depth,
      String parentKey,
      Dimension outerDim,
      Dimension innerDim,
      Map<String, TopLevelNode> innerCandidatesByKey,
      Map<String, List<TopLevelNode>> sameDimensionChildrenByParentKey,
      Set<String> expandedKeys) {
    boolean crossDimensionChild = depth > 0 && innerDim != null;
    boolean expandable =
        !crossDimensionChild
            && AutoExpansion.isNestable(outerDim)
            && !AutoExpansion.isPersonLeafBucket(node.key())
            && (innerDim != null || node.hasChildren());
    boolean expanded = expandable && expandedKeys.contains(key);
    frontier.add(new AxisNode(key, node.label(), depth, expandable, parentKey, expanded));
    if (!expanded) {
      return;
    }
    List<TopLevelNode> children =
        depth == 0 && innerDim != null
            ? List.copyOf(innerCandidatesByKey.values())
            : sameDimensionChildrenByParentKey.getOrDefault(key, List.of());
    for (TopLevelNode child : children) {
      addFrontierNode(
          frontier,
          child,
          key + "|" + child.key(),
          depth + 1,
          key,
          outerDim,
          innerDim,
          innerCandidatesByKey,
          sameDimensionChildrenByParentKey,
          expandedKeys);
    }
  }

  /** Assemble the full grid: cells, row/column suppression, and both totals. */
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
    RowColumnSuppression.Rows suppressedRows =
        RowColumnSuppression.suppressBlankRows(spec, rowNodes, cells);
    // Column blankness never depends on which rows are visible: a suppressed row's every cell was
    // already blank by construction (suppressBlankRows' own condition), so it can never be the one
    // non-blank cell keeping a column alive — checking against the pruned row set here is
    // equivalent to checking the full one, just cheaper.
    RowColumnSuppression.Columns suppressedColumns =
        RowColumnSuppression.suppressBlankColumns(
            spec, columnBucketNodes, suppressedRows.cells(), spec.measures().size());
    List<AxisNode> columnBuckets = suppressedColumns.columnBucketNodes();
    List<List<Cell>> displayCells = suppressedColumns.cells();

    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    boolean rowTotalsForbiddenByTag = axisNestsTag(axes.colDim(), axes);
    boolean columnTotalsForbiddenByTag = axisNestsTag(axes.rowDim(), axes);

    List<Cell> rowTotals =
        computeRowTotals(
            spec, displayCells, columnBuckets, rowTotalsForbiddenByTag, axes, anyClosingBalance);
    List<AxisNode> columns = renderedColumns(spec.measures(), axes.colDim(), columnBuckets);
    // A stage-e nested (depth-1) child row already contributes to its depth-0 parent's own
    // subtotal cell (§9.2 — e1 assumes subtotal throughout); summing a column or the grand total
    // over every row in the flattened frontier would therefore double-count it. Both totals sum
    // down the row axis, so both restrict to depth-0 rows here — the same double-counting hazard
    // computeRowTotals above guards against its own way, restricting to depth-0 *column buckets*
    // since it sums across columns within one row instead of down rows.
    List<List<Cell>> topLevelCells = topLevelRowsOnly(suppressedRows.rows(), displayCells);
    List<Cell> columnTotals =
        computeColumnTotals(spec, topLevelCells, columns.size(), columnTotalsForbiddenByTag, axes);
    Cell grandTotal =
        computeGrandTotal(
            spec,
            topLevelValuesOnly(suppressedRows.rows(), rowTotals),
            rowTotalsForbiddenByTag,
            columnTotalsForbiddenByTag,
            axes,
            anyClosingBalance);

    return new ReportGrid(
        suppressedRows.rows(),
        columns,
        blankGroupHeaderRows(spec, suppressedRows.rows(), displayCells),
        blankGroupHeaderRowTotals(spec, suppressedRows.rows(), rowTotals),
        columnTotals,
        grandTotal,
        resolved.start(),
        resolved.end(),
        ScopeDimensionMismatch.check(axes.nonDateDim(), spec.scope()));
  }

  /**
   * A currently-expanded parent row's own cells, blanked when {@link
   * ReportSpec#groupHeaderParents()} is on (reporting.md §9.2, plan stage e3) — applied last, after
   * suppression and every total already summed the real values, so only the *displayed* grid
   * changes; row/column/grand totals still reflect the real subtotal a header-only parent no longer
   * prints. A row only counts as a "parent" here when it is currently showing its own children
   * right beneath it ({@link AxisNode#expandable()} and {@link AxisNode#expanded()}) — a collapsed
   * node is the only row standing in for its whole subtree, so it always keeps its real aggregate
   * regardless of this setting.
   */
  private static List<List<Cell>> blankGroupHeaderRows(
      ReportSpec spec, List<AxisNode> rows, List<List<Cell>> cells) {
    if (!spec.groupHeaderParents()) {
      return cells;
    }
    List<List<Cell>> display = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      AxisNode row = rows.get(i);
      display.add(
          row.expandable() && row.expanded()
              ? cells.get(i).stream().map(c -> Cell.BLANK).toList()
              : cells.get(i));
    }
    return display;
  }

  /** {@link #blankGroupHeaderRows}'s own mirror for each row's own row-total column. */
  private static List<Cell> blankGroupHeaderRowTotals(
      ReportSpec spec, List<AxisNode> rows, List<Cell> rowTotals) {
    if (!spec.groupHeaderParents() || rowTotals.isEmpty()) {
      return rowTotals;
    }
    List<Cell> display = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      AxisNode row = rows.get(i);
      display.add(row.expandable() && row.expanded() ? Cell.BLANK : rowTotals.get(i));
    }
    return display;
  }

  /**
   * Whether {@code axisDim} — the axis's own outer (or only) dimension — carries {@link
   * Dimension#TAG} anywhere on it, including as stage e's nested inner dimension (§3): tags are not
   * leaves-only (data-model §10.3), so a total summing across overlapping tag rows/columns is
   * illegal (§7.2) whether Tag is the whole axis or just nested one level into it.
   */
  private static boolean axisNestsTag(Dimension axisDim, AxisPlan axes) {
    boolean tagNestedOnThisAxis = axisDim != null && axisDim == axes.nonDateDim();
    return axisDim == Dimension.TAG || (tagNestedOnThisAxis && axes.innerDim() == Dimension.TAG);
  }

  /** {@code rows}/{@code cells} restricted to the depth-0 (top-level, non-nested) rows. */
  private static List<List<Cell>> topLevelRowsOnly(List<AxisNode> rows, List<List<Cell>> cells) {
    List<List<Cell>> kept = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).depth() == 0) {
        kept.add(cells.get(i));
      }
    }
    return kept;
  }

  /**
   * {@code rows}-aligned {@code perRowValues} restricted to the depth-0 (top-level) rows — empty,
   * without indexing into {@code rows}, when {@code perRowValues} itself is empty ({@link
   * ReportSpec#rowTotals()} off; {@link #computeGrandTotal} already renders blank in that case).
   */
  private static List<Cell> topLevelValuesOnly(List<AxisNode> rows, List<Cell> perRowValues) {
    if (perRowValues.isEmpty()) {
      return List.of();
    }
    List<Cell> kept = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).depth() == 0) {
        kept.add(perRowValues.get(i));
      }
    }
    return kept;
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

  /**
   * One value per row: the row's own cells summed across columns. When stage e's nesting (§3, §9)
   * lands on the <em>column</em> axis instead of rows, {@code columnBucketNodes} carries depth &gt;
   * 0 buckets whose contribution is already folded into their depth-0 parent bucket's own cell (the
   * row-axis mirror of the double-counting {@link #topLevelRowsOnly} guards against) — summing
   * every column blind to that would double it, so this restricts to each row's depth-0 bucket
   * cells first (a bucket may itself render several consecutive cells, one per measure, {@link
   * #buildRowCells}'s own layout).
   */
  private List<Cell> computeRowTotals(
      ReportSpec spec,
      List<List<Cell>> cells,
      List<AxisNode> columnBucketNodes,
      boolean forbiddenByTag,
      AxisPlan axes,
      boolean anyClosingBalance) {
    if (!spec.rowTotals()) {
      return List.of();
    }
    Cell.Reason forbidden = TotalReason.forRowTotal(spec, axes, forbiddenByTag, anyClosingBalance);
    int measuresPerBucket = spec.measures().size();
    return cells.stream()
        .map(
            row ->
                sumCells(topLevelBucketCells(columnBucketNodes, row, measuresPerBucket), forbidden))
        .toList();
  }

  /**
   * {@code rowCells} restricted to the cells of {@code columnBucketNodes}' depth-0 buckets — a
   * no-op copy when nothing on the column axis nests (the common case, every bucket depth-0).
   */
  private static List<Cell> topLevelBucketCells(
      List<AxisNode> columnBucketNodes, List<Cell> rowCells, int measuresPerBucket) {
    if (columnBucketNodes.stream().allMatch(node -> node.depth() == 0)) {
      return rowCells;
    }
    List<Cell> kept = new ArrayList<>();
    for (int i = 0; i < columnBucketNodes.size(); i++) {
      if (columnBucketNodes.get(i).depth() == 0) {
        int start = i * measuresPerBucket;
        kept.addAll(rowCells.subList(start, start + measuresPerBucket));
      }
    }
    return kept;
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
        // Keeps the bucket's own depth and expanded flag, so the header still shows the
        // hierarchy the bucket sits in (stage e5); totals read columnBucketNodes, not these.
        columns.add(
            new AxisNode(
                bucket.key() + "|" + measureKey(measure),
                bucket.label() + " — " + measureLabel(measure),
                bucket.depth(),
                bucket.expandable(),
                bucket.parentKey(),
                bucket.expanded()));
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
}
