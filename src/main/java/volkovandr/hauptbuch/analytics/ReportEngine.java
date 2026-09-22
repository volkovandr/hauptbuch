package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * clear message. Filters (§6.2–§6.3) are applied by {@link
 * volkovandr.hauptbuch.analytics.repository.ReportQueryRepository}. Stage a's cross-axis cap
 * remains: at most one of the two axes may carry a non-Date dimension at all (a cross-axis
 * cartesian of two different dimensions, e.g. Category rows × Account columns, is out of scope).
 * Stage e adds nesting <em>within</em> that one axis — up to two dimensions, the second revealed by
 * expanding a node of the first (§3, §9) — via {@link #render(ReportSpec, LocalDate,
 * RowExpansion)}.
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
    return render(spec, LocalDate.now(), RowExpansion.AUTO);
  }

  /** {@link #render(ReportSpec)} with an injectable "today", for deterministic tests. */
  ReportGrid render(ReportSpec spec, LocalDate today) {
    return render(spec, today, RowExpansion.AUTO);
  }

  /**
   * {@link #render(ReportSpec, LocalDate)} taking an explicit row-expansion state (reporting.md
   * §9.2) rather than always {@link RowExpansion#AUTO} — stage e1's engine-side hook; remembering
   * the expansion against a saved Report is stage e2's job.
   */
  ReportGrid render(ReportSpec spec, LocalDate today, RowExpansion expansion) {
    AxisPlan axes = planAxes(spec);
    validateClosingBalanceHasBalance(spec, axes.nonDateDim(), axes.innerDim());
    String baseCurrency = requireBaseCurrency();

    List<String> types = List.copyOf(spec.scope().accountTypes());
    RangeResolver.ResolvedRange resolved = RangeResolver.resolve(spec.range(), today);
    List<MonthBucket> buckets = MonthBucket.monthsBetween(resolved.start(), resolved.end());
    Map<String, TopLevelNode> candidatesByKey =
        dataFetcher.candidatesFor(axes.nonDateDim(), types, spec.scope());
    Set<String> expandedOuterKeys =
        expandedOuterKeys(expansion, axes.nonDateDim(), spec, candidatesByKey);

    // Only fetch what expansion actually needs — nothing when nothing is expanded, whichever of
    // the two child sources (§3's cross-dimension nesting or §9.1's same-dimension one) applies.
    Map<String, TopLevelNode> innerCandidatesByKey = Map.of();
    Map<String, List<TopLevelNode>> sameDimensionChildrenByOuterKey = Map.of();
    if (!expandedOuterKeys.isEmpty()) {
      if (axes.innerDim() != null) {
        innerCandidatesByKey = dataFetcher.candidatesFor(axes.innerDim(), types, spec.scope());
      } else {
        sameDimensionChildrenByOuterKey =
            childCandidatesByOuterKey(axes.nonDateDim(), expandedOuterKeys, spec.scope());
      }
    }

    List<AxisNode> nestedAxisNodes =
        gridBuilder.frontierNodes(
            axes.nonDateDim(),
            axes.innerDim(),
            candidatesByKey,
            innerCandidatesByKey,
            sameDimensionChildrenByOuterKey,
            expandedOuterKeys);
    List<AxisNode> rowNodes =
        onNonDateAxis(axes.rowDim(), axes.nonDateDim())
            ? nestedAxisNodes
            : gridBuilder.axisNodes(axes.rowDim(), candidatesByKey, buckets);
    List<AxisNode> columnBucketNodes =
        onNonDateAxis(axes.colDim(), axes.nonDateDim())
            ? nestedAxisNodes
            : gridBuilder.axisNodes(axes.colDim(), candidatesByKey, buckets);

    GridData data =
        dataFetcher.fetchGridData(
            spec, axes, types, resolved, buckets, today, baseCurrency, expandedOuterKeys);

    return gridBuilder.build(
        spec, axes, rowNodes, columnBucketNodes, candidatesByKey, data, baseCurrency, resolved);
  }

  /** Each expanded outer node's own direct children (§9.1), keyed by that node's key. */
  private Map<String, List<TopLevelNode>> childCandidatesByOuterKey(
      Dimension outerDim, Set<String> expandedOuterKeys, Scope scope) {
    Map<String, List<TopLevelNode>> byOuterKey = new LinkedHashMap<>();
    for (String outerKey : expandedOuterKeys) {
      byOuterKey.put(outerKey, dataFetcher.childCandidatesFor(outerDim, outerKey, scope));
    }
    return byOuterKey;
  }

  /**
   * Whether {@code axisDim} is the axis carrying {@code nonDateDim} (never true when both null).
   */
  private static boolean onNonDateAxis(Dimension axisDim, Dimension nonDateDim) {
    return nonDateDim != null && axisDim == nonDateDim;
  }

  /**
   * Which top-level nodes of {@code outerDim} start expanded (reporting.md §9.2), given {@code
   * expansion}: {@link RowExpansion#COLLAPSED} expands none, {@link RowExpansion#EXPANDED} expands
   * every candidate, {@link RowExpansion#AUTO} defers to {@link AutoExpansion#startsExpanded}.
   * Never anything when {@code outerDim} cannot nest a second dimension at all (§9.1 — a
   * hierarchical dimension only), whether that second dimension is a different one (§3's
   * cross-dimension nesting) or {@code outerDim}'s own hierarchy one level deeper (§9.1) — and
   * never the per-currency "personal debts" pseudo-bucket, which is not a single subtree a nesting
   * filter can name (§3).
   */
  private static Set<String> expandedOuterKeys(
      RowExpansion expansion,
      Dimension outerDim,
      ReportSpec spec,
      Map<String, TopLevelNode> outerCandidatesByKey) {
    if (!AutoExpansion.isNestable(outerDim)) {
      return Set.of();
    }
    Set<String> allKeys =
        outerCandidatesByKey.keySet().stream()
            .filter(key -> !AutoExpansion.isPersonLeafBucket(key))
            .collect(Collectors.toSet());
    return switch (expansion) {
      case COLLAPSED -> Set.of();
      case EXPANDED -> allKeys;
      case AUTO -> AutoExpansion.startsExpanded(outerDim, spec) ? allKeys : Set.of();
    };
  }

  private AxisPlan planAxes(ReportSpec spec) {
    Dimension rowDim = rowSlotDimension(spec);
    Dimension colDim = spec.columns().isEmpty() ? null : spec.columns().get(0);
    validateOneNonDateDimension(rowDim, colDim);
    Dimension rowInner = rowInnerSlotDimension(spec);
    Dimension colInner = spec.columns().size() == 2 ? spec.columns().get(1) : null;
    Dimension nonDateDim;
    Dimension innerDim;
    if (rowDim != null && rowDim != Dimension.DATE) {
      nonDateDim = rowDim;
      innerDim = rowInner;
    } else if (colDim != null && colDim != Dimension.DATE) {
      nonDateDim = colDim;
      innerDim = colInner;
    } else {
      nonDateDim = null;
      innerDim = null;
    }
    return new AxisPlan(
        rowDim, colDim, nonDateDim, innerDim, rowDim == Dimension.DATE, colDim == Dimension.DATE);
  }

  /**
   * {@code series} (stage b, chart-only) fills the same engine slot {@code rows} would when {@code
   * rows} is empty — {@link ReportSpec}'s own constructor already refuses a spec carrying both, so
   * this never has to choose between them. {@code series} is capped at one dimension, so it never
   * contributes a nested inner dimension (see {@link #rowInnerSlotDimension}).
   */
  private static Dimension rowSlotDimension(ReportSpec spec) {
    if (!spec.rows().isEmpty()) {
      return spec.rows().get(0);
    }
    return spec.series().isEmpty() ? null : spec.series().get(0);
  }

  /** Stage e's second, nested row dimension (§3) — {@code null} unless {@code rows} names two. */
  private static Dimension rowInnerSlotDimension(ReportSpec spec) {
    return spec.rows().size() == 2 ? spec.rows().get(1) : null;
  }

  private static void validateOneNonDateDimension(Dimension rowDim, Dimension colDim) {
    if (rowDim == Dimension.DATE && colDim == Dimension.DATE) {
      throw new UnsupportedOperationException("Date cannot be on both rows and columns.");
    }
    boolean rowIsNonDate = rowDim != null && rowDim != Dimension.DATE;
    boolean colIsNonDate = colDim != null && colDim != Dimension.DATE;
    if (rowIsNonDate && colIsNonDate) {
      throw new UnsupportedOperationException(
          "Two different non-Date dimensions on rows and columns — a cross-axis cartesian — is out"
              + " of scope (reporting.md §3); nesting two dimensions on the same axis is stage e.");
    }
  }

  /**
   * {@link Dimension#TAG} and {@link Dimension#PAYEE} name no standing balance — a tag is not an
   * account, and a payee is a transaction attribute, not a thing that is held (reporting.md §4).
   * Checked for both the outer and stage e's nested inner dimension, since either can carry one.
   */
  private static void validateClosingBalanceHasBalance(
      ReportSpec spec, Dimension nonDateDim, Dimension innerDim) {
    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    if (!anyClosingBalance) {
      return;
    }
    rejectIfBalanceless(nonDateDim);
    rejectIfBalanceless(innerDim);
  }

  private static void rejectIfBalanceless(Dimension dimension) {
    if (dimension == Dimension.TAG) {
      throw new UnsupportedOperationException(
          "A tag has no closing balance (it is not an account).");
    }
    if (dimension == Dimension.PAYEE) {
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
}
