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
 * expanding a node of the first (§3, §9). {@link #render(ReportSpec, LocalDate, Set)} takes a saved
 * Report's remembered, hand-toggled expansion state (§9.1); {@link #render(ReportSpec, LocalDate,
 * RowExpansion)} is the uniform-all-or-nothing form e1's own tests still use.
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
   * §9.2) rather than always {@link RowExpansion#AUTO} — stage e1's engine-side hook, still used by
   * its own tests; a saved Report's remembered per-node state uses {@link #render(ReportSpec,
   * LocalDate, Set)} instead (stage e2).
   */
  ReportGrid render(ReportSpec spec, LocalDate today, RowExpansion expansion) {
    return render(spec, today, expansion, null);
  }

  /**
   * {@link #render(ReportSpec, LocalDate)} taking the remembered, hand-toggled set of expanded
   * top-level keys (reporting.md §9.1) for a saved Report — {@code null} means no explicit state
   * exists yet and {@code auto} (§9.2) decides, same as {@link #render(ReportSpec, LocalDate)}; a
   * non-null set (possibly empty) is used literally, intersected with whichever candidates still
   * exist. A Preset or an unsaved draft always renders with {@code null} — expansion state is
   * remembered only against a saved Report (§9.1).
   */
  ReportGrid render(ReportSpec spec, LocalDate today, Set<String> explicitExpandedKeys) {
    return render(spec, today, RowExpansion.AUTO, explicitExpandedKeys);
  }

  private ReportGrid render(
      ReportSpec spec, LocalDate today, RowExpansion expansion, Set<String> explicitOverride) {
    AxisPlan axes = planAxes(spec);
    validateClosingBalanceHasBalance(spec, axes.nonDateDim(), axes.innerDim());
    String baseCurrency = requireBaseCurrency();

    List<String> types = List.copyOf(spec.scope().accountTypes());
    RangeResolver.ResolvedRange resolved = RangeResolver.resolve(spec.range(), today);
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            spec.dateLadder().bucketGranularity(), resolved.start(), resolved.end());
    Map<String, TopLevelNode> candidatesByKey =
        dataFetcher.candidatesFor(axes.nonDateDim(), types, spec.scope());
    Set<String> expandedKeys =
        expandedOuterKeys(expansion, explicitOverride, axes.nonDateDim(), spec, candidatesByKey);

    // Only fetch what expansion actually needs — nothing when nothing is expanded, whichever of
    // the two child sources (§3's cross-dimension nesting or §9.1's same-dimension one) applies.
    // The same-dimension source is fetched for every expanded key regardless of its own depth
    // (§9.1 recurses to arbitrary depth) — a depth-1+ key's real id is recovered from its own
    // composite key by ReportDataFetcher itself.
    Map<String, TopLevelNode> innerCandidatesByKey = Map.of();
    Map<String, List<TopLevelNode>> sameDimensionChildrenByParentKey = Map.of();
    if (!expandedKeys.isEmpty()) {
      if (axes.innerDim() != null) {
        innerCandidatesByKey = dataFetcher.candidatesFor(axes.innerDim(), types, spec.scope());
      } else {
        sameDimensionChildrenByParentKey =
            childCandidatesByParentKey(axes.nonDateDim(), expandedKeys, spec.scope());
      }
    }

    List<AxisNode> nestedAxisNodes =
        gridBuilder.frontierNodes(
            axes.nonDateDim(),
            axes.innerDim(),
            candidatesByKey,
            innerCandidatesByKey,
            sameDimensionChildrenByParentKey,
            expandedKeys);
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
            spec, axes, types, resolved, buckets, today, baseCurrency, expandedKeys);

    return gridBuilder.build(
        spec, axes, rowNodes, columnBucketNodes, candidatesByKey, data, baseCurrency, resolved);
  }

  /**
   * Each expanded node's own direct children (§9.1), keyed by that node's own (possibly composite,
   * any depth) key.
   */
  private Map<String, List<TopLevelNode>> childCandidatesByParentKey(
      Dimension outerDim, Set<String> expandedKeys, Scope scope) {
    Map<String, List<TopLevelNode>> byParentKey = new LinkedHashMap<>();
    for (String key : expandedKeys) {
      byParentKey.put(key, dataFetcher.childCandidatesFor(outerDim, key, scope));
    }
    return byParentKey;
  }

  /**
   * Whether {@code axisDim} is the axis carrying {@code nonDateDim} (never true when both null).
   */
  private static boolean onNonDateAxis(Dimension axisDim, Dimension nonDateDim) {
    return nonDateDim != null && axisDim == nonDateDim;
  }

  /**
   * Which nodes of {@code outerDim} are expanded, given a stage e2 {@code explicitOverride}
   * (reporting.md §9.1) when one exists, or else {@code expansion}'s uniform rule (§9.2): {@link
   * RowExpansion#COLLAPSED} expands none, {@link RowExpansion#EXPANDED} expands every top-level
   * candidate, {@link RowExpansion#AUTO} expands exactly the one node {@link
   * AutoExpansion#autoExpandedKeys} names (never "every top-level candidate", even when the filter
   * happens to select one of several). Never anything when {@code outerDim} cannot nest a second
   * dimension at all (§9.1 — a hierarchical dimension only), and never the per-currency "personal
   * debts" pseudo-bucket, which is not a single subtree a nesting filter can name (§3).
   *
   * <p>The returned set can carry a nested (depth &gt; 0) node's own composite key too — same-
   * dimension nesting recurses to arbitrary depth (§9.1), not just one level into the top-level
   * candidates this method itself fetches. Such a key is never validated against {@code
   * outerCandidatesByKey} (which only ever lists <em>top-level</em> nodes) — its own liveness is
   * proven downstream, the moment its parent's own child-candidates fetch actually returns it;
   * until then it is simply never referenced while walking the frontier (mirrors how a stale
   * top-level key already degrades gracefully here).
   */
  private static Set<String> expandedOuterKeys(
      RowExpansion expansion,
      Set<String> explicitOverride,
      Dimension outerDim,
      ReportSpec spec,
      Map<String, TopLevelNode> outerCandidatesByKey) {
    if (!AutoExpansion.isNestable(outerDim)) {
      return Set.of();
    }
    Set<String> topLevelKeys =
        outerCandidatesByKey.keySet().stream()
            .filter(key -> !AutoExpansion.isPersonLeafBucket(key))
            .collect(Collectors.toSet());
    if (explicitOverride != null) {
      return explicitOverride.stream()
          .filter(key -> isNestedKey(key) || topLevelKeys.contains(key))
          .collect(Collectors.toSet());
    }
    return switch (expansion) {
      case COLLAPSED -> Set.of();
      case EXPANDED -> topLevelKeys;
      case AUTO ->
          AutoExpansion.autoExpandedKeys(outerDim, spec).stream()
              .filter(topLevelKeys::contains)
              .collect(Collectors.toSet());
    };
  }

  /**
   * A depth &gt; 0 node's own composite frontier key (reporting.md §9.1) — see {@link AxisNode}.
   */
  private static boolean isNestedKey(String key) {
    return key != null && key.indexOf('|') >= 0;
  }

  /**
   * The expanded top-level key set a toggle handler sees just before flipping one key (stage e2):
   * {@code explicitOverride} echoed back, intersected with the dimension's real current candidates,
   * when it is not {@code null}; otherwise {@code auto}'s own uniform rule (§9.2), materialized
   * against those same candidates. Fetches only what {@link #expandedOuterKeys} needs to decide —
   * the outer dimension's candidates — never the axis's full data.
   */
  Set<String> effectiveExpandedKeys(ReportSpec spec, Set<String> explicitOverride) {
    AxisPlan axes = planAxes(spec);
    List<String> types = List.copyOf(spec.scope().accountTypes());
    Map<String, TopLevelNode> candidatesByKey =
        dataFetcher.candidatesFor(axes.nonDateDim(), types, spec.scope());
    return expandedOuterKeys(
        RowExpansion.AUTO, explicitOverride, axes.nonDateDim(), spec, candidatesByKey);
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
