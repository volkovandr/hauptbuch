package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Turns {@link ReportEngine}'s fetched raw data into a {@link ReportGrid}: axis assembly, the
 * credit-natural display flip (data-model §4.1), the legality rules (reporting.md §7.2), row
 * suppression (§7.3) and totals (§7.1). Split out from {@link ReportEngine} (which owns talking to
 * the repository and settings) so each class stays focused on one concern.
 */
// CouplingBetweenObjects: this class's whole job is turning every raw-data and spec vocabulary
// type (Cell, AxisNode, AxisPlan, GridData, RawTurnoverCell, RawBalanceCell, TopLevelNode,
// Measure, ...) into a ReportGrid — a mapping step, not a service with many behavioural
// collaborators. It was already split once out of ReportEngine (which owns the
// repository/settings dependencies); splitting the cell-valuation half out again would still
// leave both halves referencing most of the same small records, since they describe one grid's
// cells. Suppressing here rather than forcing a seam the domain doesn't actually have.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
class ReportGridBuilder {

  private static final String TOTAL_KEY = "total";
  private static final Set<String> CREDIT_NATURAL_TYPES = Set.of("income", "liability", "equity");

  private final ExchangeRateService exchangeRateService;

  ReportGridBuilder(ExchangeRateService exchangeRateService) {
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * The axis labels for one axis: {@code axisDim}'s candidates, the Date buckets, or one implicit
   * row/column.
   */
  List<AxisNode> axisNodes(
      Dimension axisDim, Map<String, TopLevelNode> candidatesByKey, List<MonthBucket> buckets) {
    if (axisDim == null) {
      return List.of(new AxisNode(TOTAL_KEY, "Total"));
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
    CellContext context = new CellContext(axes, candidatesByKey, data, baseCurrency, spec.scope());
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
        resolved.end());
  }

  private List<List<Cell>> buildCells(
      ReportSpec spec,
      List<AxisNode> rowNodes,
      List<AxisNode> columnBucketNodes,
      CellContext context) {
    return rowNodes.stream()
        .map(rowNode -> buildRowCells(spec, rowNode, columnBucketNodes, context))
        .toList();
  }

  private List<Cell> buildRowCells(
      ReportSpec spec, AxisNode rowNode, List<AxisNode> columnBucketNodes, CellContext context) {
    List<Cell> rowCells = new ArrayList<>();
    for (AxisNode bucketNode : columnBucketNodes) {
      for (Measure measure : spec.measures()) {
        rowCells.add(computeCell(measure, rowNode, bucketNode, context));
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
    // A row total sums across every rendered column. When there is more than one measure, those
    // columns are different presentations of one figure (e.g. base vs. native), not additive
    // quantities — summing them would silently double-count rather than total anything real.
    boolean forbidden =
        forbiddenByTag
            || spec.measures().size() > 1
            || (axes.colDim() == Dimension.DATE && anyClosingBalance);
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
      boolean forbidden =
          forbiddenByTag
              || (axes.rowDim() == Dimension.DATE
                  && measureForColumn(spec.measures(), columnIndex).kind()
                      == MeasureKind.CLOSING_BALANCE);
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
    boolean forbidden =
        rowTotalsForbiddenByTag
            || columnTotalsForbiddenByTag
            || ((axes.rowDim() == Dimension.DATE || axes.colDim() == Dimension.DATE)
                && anyClosingBalance);
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

  private Cell computeCell(
      Measure measure, AxisNode rowNode, AxisNode columnBucketNode, CellContext context) {
    AxisPlan axes = context.axes();
    Dimension nonDateDim = axes.nonDateDim();
    String dimKey =
        nonDateDim == null
            ? TOTAL_KEY
            : (axes.rowDim() == nonDateDim ? rowNode.key() : columnBucketNode.key());
    boolean creditNatural = isCreditNatural(context.candidatesByKey().get(dimKey), context.scope());

    String monthKey =
        axes.dateOnRows() ? rowNode.key() : axes.dateOnColumns() ? columnBucketNode.key() : null;
    if (measure.kind() == MeasureKind.TURNOVER) {
      List<RawTurnoverCell> matches =
          turnoverMatches(measure.leg(), dimKey, monthKey, context.data());
      return turnoverCellValue(matches, measure, context.baseCurrency(), creditNatural);
    }
    if (measure.kind() == MeasureKind.COUNT_POSTINGS
        || measure.kind() == MeasureKind.COUNT_TRANSACTIONS) {
      List<RawTurnoverCell> matches = turnoverMatches(Leg.NET, dimKey, monthKey, context.data());
      return countCellValue(matches, measure.kind());
    }
    return computeClosingBalanceCell(
        measure, rowNode, columnBucketNode, dimKey, creditNatural, axes, context);
  }

  private Cell computeClosingBalanceCell(
      Measure measure,
      AxisNode rowNode,
      AxisNode columnBucketNode,
      String dimKey,
      boolean creditNatural,
      AxisPlan axes,
      CellContext context) {
    String bucketKey =
        axes.dateOnRows()
            ? rowNode.key()
            : axes.dateOnColumns() ? columnBucketNode.key() : TOTAL_KEY;
    List<RawBalanceCell> raw =
        context.data().balanceByBucketKey().getOrDefault(bucketKey, List.of());
    LocalDate asOf = context.data().asOfByBucketKey().get(bucketKey);
    List<RawBalanceCell> matches =
        raw.stream().filter(c -> c.dimensionKey().equals(dimKey)).toList();
    return balanceCellValue(matches, measure, context.baseCurrency(), asOf, creditNatural);
  }

  /**
   * The credit-natural flip for one cell: a node with a single known type (an account-tree row, or
   * an {@link Dimension#ACCOUNT_TYPE}/{@link Dimension#PERSON} row, both unambiguous) flips by its
   * own type; a node with none — no row/column dimension at all, or a dimension spanning more than
   * one type ({@link Dimension#CURRENCY}, {@link Dimension#PAYEE}) — falls back to {@link
   * #isCreditNaturalScope}.
   */
  private static boolean isCreditNatural(TopLevelNode node, Scope scope) {
    if (node != null && node.type() != null) {
      return CREDIT_NATURAL_TYPES.contains(node.type());
    }
    return isCreditNaturalScope(scope);
  }

  /**
   * The credit-natural flip for a report with no row/column dimension (a plain total), or a
   * dimension whose node carries no single type: flips only when every account type in scope shares
   * the credit-natural side, so a total mixing income and expense — which has no single correct
   * sign — is left unflipped rather than guessing.
   */
  private static boolean isCreditNaturalScope(Scope scope) {
    return !scope.accountTypes().isEmpty()
        && scope.accountTypes().stream().allMatch(CREDIT_NATURAL_TYPES::contains);
  }

  private static List<RawTurnoverCell> turnoverMatches(
      Leg leg, String dimKey, String monthKey, GridData data) {
    List<RawTurnoverCell> raw = data.turnoverByLeg().getOrDefault(leg, List.of());
    return raw.stream()
        .filter(c -> c.dimensionKey().equals(dimKey))
        .filter(c -> monthKey == null || c.monthKey().equals(monthKey))
        .toList();
  }

  /**
   * A count measure's value (§5.5): the raw rows are still partitioned by currency (the same
   * NET-leg turnover data every measure shares), so {@link RawTurnoverCell#postingCount()} sums
   * exactly — a posting belongs to exactly one currency group. {@link
   * RawTurnoverCell#transactionCount()} is each currency group's own distinct-transaction count;
   * summing them over-counts a transaction whose legs in this cell span more than one currency
   * (rare — a cross-currency split within one category/month), counting it once per currency
   * touched rather than once overall. Not corrected here: doing so needs the raw transaction ids,
   * which the aggregated query does not carry.
   */
  private static Cell countCellValue(List<RawTurnoverCell> matches, MeasureKind kind) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    long total =
        matches.stream()
            .mapToLong(
                kind == MeasureKind.COUNT_POSTINGS
                    ? RawTurnoverCell::postingCount
                    : RawTurnoverCell::transactionCount)
            .sum();
    return new Cell.Count(total);
  }

  private Cell turnoverCellValue(
      List<RawTurnoverCell> matches, Measure measure, String baseCurrency, boolean creditNatural) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    if (measure.currency() == PresentationCurrency.ACCOUNT) {
      Set<String> currencies =
          matches.stream().map(RawTurnoverCell::currencyCode).collect(Collectors.toSet());
      if (currencies.size() > 1) {
        return Cell.ILLEGAL;
      }
      BigDecimal sum =
          matches.stream()
              .map(RawTurnoverCell::nativeAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      return new Cell.Value(creditNatural ? sum.negate() : sum, currencies.iterator().next());
    }
    boolean missingRate = matches.stream().anyMatch(c -> c.missingRateCount() > 0);
    if (missingRate) {
      return Cell.ILLEGAL;
    }
    BigDecimal sum =
        matches.stream().map(RawTurnoverCell::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Cell.Value(creditNatural ? sum.negate() : sum, baseCurrency);
  }

  private Cell balanceCellValue(
      List<RawBalanceCell> matches,
      Measure measure,
      String baseCurrency,
      LocalDate asOf,
      boolean creditNatural) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    if (measure.currency() == PresentationCurrency.ACCOUNT) {
      Set<String> currencies =
          matches.stream().map(RawBalanceCell::currencyCode).collect(Collectors.toSet());
      if (currencies.size() > 1) {
        return Cell.ILLEGAL;
      }
      BigDecimal sum =
          matches.stream()
              .map(RawBalanceCell::nativeBalance)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      return new Cell.Value(creditNatural ? sum.negate() : sum, currencies.iterator().next());
    }
    BigDecimal total = BigDecimal.ZERO;
    for (RawBalanceCell cell : matches) {
      if (cell.currencyCode().equals(baseCurrency)) {
        total = total.add(cell.nativeBalance());
        continue;
      }
      Optional<BigDecimal> rate = exchangeRateService.rateAsOf(cell.currencyCode(), asOf);
      if (rate.isEmpty()) {
        return Cell.ILLEGAL;
      }
      total = total.add(cell.nativeBalance().multiply(rate.get()));
    }
    return new Cell.Value(creditNatural ? total.negate() : total, baseCurrency);
  }

  /**
   * Sum a row or column of cells into its total — illegal if {@code forbidden} (the tag/time rules,
   * §7.2), if any addend is itself illegal, or if the addends span more than one currency (the same
   * §5.4 rule a per-cell account-currency measure obeys, now applied to the total).
   */
  private static Cell sumCells(List<Cell> cells, boolean forbidden) {
    if (forbidden) {
      return Cell.ILLEGAL;
    }
    if (cells.stream().anyMatch(c -> c instanceof Cell.Illegal)) {
      return Cell.ILLEGAL;
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
      return Cell.ILLEGAL;
    }
    BigDecimal sum =
        values.stream().map(Cell.Value::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Cell.Value(sum, currencies.iterator().next());
  }

  /** Everything {@link #computeCell} needs, bundled to keep its parameter list short. */
  private record CellContext(
      AxisPlan axes,
      Map<String, TopLevelNode> candidatesByKey,
      GridData data,
      String baseCurrency,
      Scope scope) {}

  /** The row axis and cells after {@link ReportSpec#suppressEmptyRows()} is applied. */
  private record Suppressed(List<AxisNode> rows, List<List<Cell>> cells) {}
}
