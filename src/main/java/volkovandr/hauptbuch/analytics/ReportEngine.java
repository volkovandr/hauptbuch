package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The report engine's public API: {@code spec in -> grid out} (reporting.md §14). Validates the
 * spec and resolves its axes and date range; {@link ReportDataFetcher} fetches the grouped data and
 * {@link ReportGridBuilder} turns it into the {@link ReportGrid} (the two valuation rules,
 * legality, suppression and totals).
 *
 * <p><strong>Wired in this slice:</strong> {@link Dimension#CATEGORY}/{@link Dimension#ACCOUNT}
 * (identical shape, differing only in {@link Scope#accountTypes()}), {@link Dimension#TAG}
 * (turnover only — a tag has no balance), {@link Dimension#DATE} at month granularity, and no
 * dimension at all, on either rows or columns, for {@link MeasureKind#TURNOVER} and {@link
 * MeasureKind#CLOSING_BALANCE}. {@link Dimension#PAYEE}, {@code PERSON}, {@code CURRENCY}, {@code
 * ACCOUNT_TYPE}, the count measures, filters, and {@link Scope#accountSubtreeRoots()} are declared
 * in the vocabulary but rejected here with a clear {@link UnsupportedOperationException} — a
 * follow-up within stage a wires them, rather than this slice silently mis-grouping.
 */
@Service
public class ReportEngine {

  private final SettingsService settingsService;
  private final ReportDataFetcher dataFetcher;
  private final ReportGridBuilder gridBuilder;

  ReportEngine(
      SettingsService settingsService,
      ReportDataFetcher dataFetcher,
      ReportGridBuilder gridBuilder) {
    this.settingsService = settingsService;
    this.dataFetcher = dataFetcher;
    this.gridBuilder = gridBuilder;
  }

  /** Render a Report's grid, resolving the date range against today. */
  public ReportGrid render(ReportSpec spec) {
    return render(spec, LocalDate.now());
  }

  /** {@link #render(ReportSpec)} with an injectable "today", for deterministic tests. */
  ReportGrid render(ReportSpec spec, LocalDate today) {
    validateSupported(spec);
    AxisPlan axes = planAxes(spec);
    String baseCurrency = requireBaseCurrency();

    List<String> types = List.copyOf(spec.scope().accountTypes());
    RangeResolver.ResolvedRange resolved = RangeResolver.resolve(spec.range(), today);
    List<MonthBucket> buckets = MonthBucket.monthsBetween(resolved.start(), resolved.end());
    Map<String, TopLevelNode> candidatesByKey =
        dataFetcher.candidatesFor(axes.nonDateDim(), types, spec.scope());
    List<AxisNode> rowNodes = gridBuilder.axisNodes(axes.rowDim(), candidatesByKey, buckets);
    List<AxisNode> columnBucketNodes =
        gridBuilder.axisNodes(axes.colDim(), candidatesByKey, buckets);

    GridData data =
        dataFetcher.fetchGridData(spec, axes, types, resolved, buckets, today, baseCurrency);

    return gridBuilder.build(
        spec, axes, rowNodes, columnBucketNodes, candidatesByKey, data, baseCurrency, resolved);
  }

  private void validateSupported(ReportSpec spec) {
    if (!spec.filters().isEmpty()) {
      throw new UnsupportedOperationException("Filters ship in a follow-up within stage a.");
    }
    if (!spec.scope().accountSubtreeRoots().isEmpty()) {
      throw new UnsupportedOperationException(
          "Scope subtree restriction ships in a follow-up within stage a.");
    }
    boolean anyCountMeasure =
        spec.measures().stream()
            .anyMatch(
                m ->
                    m.kind() == MeasureKind.COUNT_POSTINGS
                        || m.kind() == MeasureKind.COUNT_TRANSACTIONS);
    if (anyCountMeasure) {
      throw new UnsupportedOperationException("Count measures ship in a follow-up within stage a.");
    }
  }

  private AxisPlan planAxes(ReportSpec spec) {
    Dimension rowDim = spec.rows().isEmpty() ? null : spec.rows().get(0);
    Dimension colDim = spec.columns().isEmpty() ? null : spec.columns().get(0);
    validateOneNonDateDimension(rowDim, colDim);
    Dimension nonDateDim = nonDateDimensionOf(rowDim, colDim);
    validateDimensionIsWired(nonDateDim);
    validateClosingBalanceHasBalance(spec, nonDateDim);
    return new AxisPlan(
        rowDim, colDim, nonDateDim, rowDim == Dimension.DATE, colDim == Dimension.DATE);
  }

  private static void validateOneNonDateDimension(Dimension rowDim, Dimension colDim) {
    if (rowDim == Dimension.DATE && colDim == Dimension.DATE) {
      throw new UnsupportedOperationException("Date cannot be on both rows and columns.");
    }
    boolean rowIsNonDate = rowDim != null && rowDim != Dimension.DATE;
    boolean colIsNonDate = colDim != null && colDim != Dimension.DATE;
    if (rowIsNonDate && colIsNonDate) {
      throw new UnsupportedOperationException(
          "Two different non-Date dimensions on rows and columns ship in a follow-up within"
              + " stage a.");
    }
  }

  private static void validateDimensionIsWired(Dimension nonDateDim) {
    boolean wired =
        nonDateDim == null
            || nonDateDim == Dimension.CATEGORY
            || nonDateDim == Dimension.ACCOUNT
            || nonDateDim == Dimension.TAG;
    if (!wired) {
      throw new UnsupportedOperationException(nonDateDim + " ships in a follow-up within stage a.");
    }
  }

  private static void validateClosingBalanceHasBalance(ReportSpec spec, Dimension nonDateDim) {
    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    if (anyClosingBalance && nonDateDim == Dimension.TAG) {
      throw new UnsupportedOperationException(
          "A tag has no closing balance (it is not an account).");
    }
  }

  private String requireBaseCurrency() {
    return settingsService
        .baseCurrency()
        .orElseThrow(
            () -> new IllegalStateException("Base currency must be set before reporting."));
  }

  private static Dimension nonDateDimensionOf(Dimension rowDim, Dimension colDim) {
    if (rowDim != null && rowDim != Dimension.DATE) {
      return rowDim;
    }
    if (colDim != null && colDim != Dimension.DATE) {
      return colDim;
    }
    return null;
  }
}
