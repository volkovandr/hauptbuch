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
 * <p>Every dimension in the catalogue (reporting.md §4) is wired, on either rows or columns, for
 * {@link MeasureKind#TURNOVER} and the count measures; {@link MeasureKind#CLOSING_BALANCE} is wired
 * for every dimension that names a standing balance ({@link Dimension#CATEGORY}/{@link
 * Dimension#ACCOUNT}/{@link Dimension#PERSON}/{@link Dimension#CURRENCY}/{@link
 * Dimension#ACCOUNT_TYPE}, plus no dimension at all) — {@link Dimension#TAG} and {@link
 * Dimension#PAYEE} have no closing balance (neither is an account) and are rejected for it with a
 * clear message. Filters (§6.2–§6.3) and {@link Scope#accountSubtreeRoots()} (§6.1) are applied by
 * {@link volkovandr.hauptbuch.analytics.repository.ReportQueryRepository}. Stage a's own cap
 * remains: at most one dimension per axis, and at most one of the two axes may carry a non-Date
 * dimension at all (nesting two different non-Date dimensions across axes is stage e's job).
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
    AxisPlan axes = planAxes(spec);
    validateClosingBalanceHasBalance(spec, axes.nonDateDim());
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

  private AxisPlan planAxes(ReportSpec spec) {
    Dimension rowDim = spec.rows().isEmpty() ? null : spec.rows().get(0);
    Dimension colDim = spec.columns().isEmpty() ? null : spec.columns().get(0);
    validateOneNonDateDimension(rowDim, colDim);
    Dimension nonDateDim = nonDateDimensionOf(rowDim, colDim);
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
          "Two different non-Date dimensions on rows and columns — nesting — is stage e.");
    }
  }

  /**
   * {@link Dimension#TAG} and {@link Dimension#PAYEE} name no standing balance — a tag is not an
   * account, and a payee is a transaction attribute, not a thing that is held (reporting.md §4).
   */
  private static void validateClosingBalanceHasBalance(ReportSpec spec, Dimension nonDateDim) {
    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    if (anyClosingBalance && nonDateDim == Dimension.TAG) {
      throw new UnsupportedOperationException(
          "A tag has no closing balance (it is not an account).");
    }
    if (anyClosingBalance && nonDateDim == Dimension.PAYEE) {
      throw new UnsupportedOperationException(
          "A payee has no closing balance (it is not an account).");
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
