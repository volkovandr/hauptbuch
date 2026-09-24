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
 * spec and resolves its axes and date range; {@link AxisCandidates} lists each axis's nodes, {@link
 * ReportDataFetcher} fetches the grouped data and {@link ReportGridBuilder} turns it into the
 * {@link ReportGrid} (the two valuation rules, legality, suppression and totals).
 *
 * <p>Every dimension in the catalogue (reporting.md §4) is wired, on either rows or columns, for
 * {@link MeasureKind#TURNOVER} and the count measures; {@link MeasureKind#CLOSING_BALANCE} is wired
 * for every dimension that names a standing balance ({@link Dimension#CATEGORY}/{@link
 * Dimension#ACCOUNT}/{@link Dimension#PERSON}/{@link Dimension#CURRENCY}/{@link
 * Dimension#ACCOUNT_TYPE}, plus no dimension at all) — {@link Dimension#TAG} and {@link
 * Dimension#PAYEE} have no closing balance (neither is an account) and are refused for it: the grid
 * comes back empty, carrying the reason ({@link ReportGrid#refusalMessage()}). Filters (§6.2–§6.3)
 * are applied by {@link volkovandr.hauptbuch.analytics.repository.ReportQueryRepository}. Stage a's
 * cross-axis cap remains: at most one of the two axes may carry a non-Date dimension at all (a
 * cross-axis cartesian of two different dimensions, e.g. Category rows × Account columns, is out of
 * scope). Stage e adds nesting <em>within</em> that one axis — up to two dimensions, the second
 * revealed by expanding a node of the first (§3, §9). {@link #render(ReportSpec, LocalDate, Set)}
 * takes a saved Report's remembered, hand-toggled expansion state (§9.1); {@link
 * #render(ReportSpec, LocalDate, RowExpansion)} is the uniform-all-or-nothing form e1's own tests
 * still use.
 */
@Service
public class ReportEngine {

  private final SettingsService settingsService;
  private final AxisCandidates axisCandidates;
  private final ReportDataFetcher dataFetcher;
  private final ReportGridBuilder gridBuilder;

  ReportEngine(
      SettingsService settingsService,
      AxisCandidates axisCandidates,
      ReportDataFetcher dataFetcher,
      ReportGridBuilder gridBuilder) {
    this.settingsService = settingsService;
    this.axisCandidates = axisCandidates;
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
    RangeResolver.ResolvedRange resolved = RangeResolver.resolve(spec.range(), today);
    AxisPlan axes = planAxes(spec);
    String refusal = refusal(spec, axes);
    if (refusal != null) {
      return refusedGrid(refusal, resolved);
    }
    return renderAccepted(spec, today, expansion, explicitOverride, resolved, axes);
  }

  /** {@link #render} for a spec {@link #refusal} accepted. */
  private ReportGrid renderAccepted(
      ReportSpec spec,
      LocalDate today,
      RowExpansion expansion,
      Set<String> explicitOverride,
      RangeResolver.ResolvedRange resolved,
      AxisPlan axes) {
    String baseCurrency = requireBaseCurrency();

    List<String> types = List.copyOf(spec.scope().accountTypes());
    List<DateBucket> buckets = bucketsFor(spec, resolved);
    Map<String, TopLevelNode> candidatesByKey =
        axisCandidates.candidatesFor(axes.nonDateDim(), spec);
    // A saved Report's remembered state belongs to its row tree (§9.1): with Date on rows it names
    // Date buckets, so the non-Date dimension (then on columns) falls back to its own uniform rule.
    Set<String> expandedKeys =
        expandedOuterKeys(
            expansion,
            axes.dateOnRows() ? null : explicitOverride,
            axes.nonDateDim(),
            spec,
            candidatesByKey);
    List<DateBucket> expandedDateBuckets =
        axes.dateOnRows() ? expandedDateBuckets(expansion, explicitOverride, buckets) : List.of();

    // Only fetch what expansion actually needs — nothing when nothing is expanded, whichever of
    // the two child sources (§3's cross-dimension nesting or §9.1's same-dimension one) applies.
    // The same-dimension source is fetched for every expanded key regardless of its own depth
    // (§9.1 recurses to arbitrary depth) — a depth-1+ key's real id is recovered from its own
    // composite key by ReportDataFetcher itself.
    Map<String, TopLevelNode> innerCandidatesByKey = Map.of();
    Map<String, List<TopLevelNode>> sameDimensionChildrenByParentKey = Map.of();
    if (!expandedKeys.isEmpty()) {
      if (axes.innerDim() != null) {
        innerCandidatesByKey = axisCandidates.candidatesFor(axes.innerDim(), spec);
      } else {
        sameDimensionChildrenByParentKey =
            childCandidatesByParentKey(axes.nonDateDim(), expandedKeys, spec);
      }
    }

    GridData data =
        dataFetcher.fetchGridData(
            spec, axes, types, resolved, buckets, today, baseCurrency, expandedKeys);
    // An expanded Date row's days re-run the same fetch over just that bucket's range (§9.1), so
    // every measure, filter and column nesting stays consistent and the days sum back to it.
    for (DateBucket bucket : expandedDateBuckets) {
      GridData days =
          dataFetcher.fetchGridData(
              spec,
              axes,
              types,
              bucket.effectiveRange(),
              bucket.days(),
              today,
              baseCurrency,
              expandedKeys,
              DateGranularity.DAY);
      data = data.withDays(bucket.key(), days);
    }
    candidatesByKey =
        PromotedNodes.withoutUntouchedRoots(
            axes.nonDateDim(), spec, candidatesByKey, data::hasDataFor);
    innerCandidatesByKey =
        PromotedNodes.withoutUntouchedRoots(
            axes.innerDim(), spec, innerCandidatesByKey, data::hasNestedDataFor);

    List<AxisNode> nestedAxisNodes =
        gridBuilder.frontierNodes(
            axes.nonDateDim(),
            axes.innerDim(),
            candidatesByKey,
            innerCandidatesByKey,
            sameDimensionChildrenByParentKey,
            expandedKeys);
    List<AxisNode> rowNodes;
    if (axes.dateOnRows()) {
      rowNodes = gridBuilder.dateFrontierNodes(buckets, keysOf(expandedDateBuckets));
    } else if (onNonDateAxis(axes.rowDim(), axes.nonDateDim())) {
      rowNodes = nestedAxisNodes;
    } else {
      rowNodes = gridBuilder.axisNodes(axes.rowDim(), candidatesByKey, buckets);
    }
    List<AxisNode> columnBucketNodes =
        onNonDateAxis(axes.colDim(), axes.nonDateDim())
            ? nestedAxisNodes
            : gridBuilder.axisNodes(axes.colDim(), candidatesByKey, buckets);

    return gridBuilder.build(
        spec, axes, rowNodes, columnBucketNodes, candidatesByKey, data, baseCurrency, resolved);
  }

  /**
   * Each expanded node's own direct children (§9.1), keyed by that node's own (possibly composite,
   * any depth) key.
   */
  private Map<String, List<TopLevelNode>> childCandidatesByParentKey(
      Dimension outerDim, Set<String> expandedKeys, ReportSpec spec) {
    Map<String, List<TopLevelNode>> byParentKey = new LinkedHashMap<>();
    for (String key : expandedKeys) {
      byParentKey.put(key, axisCandidates.childCandidatesFor(outerDim, key, spec));
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
   * dimension at all (§9.1 — a hierarchical dimension only).
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
    Set<String> topLevelKeys = Set.copyOf(outerCandidatesByKey.keySet());
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
   * Which Date row buckets are expanded into their days (reporting.md §9.1): {@code
   * explicitOverride} intersected with the buckets the range actually has, when it exists;
   * otherwise every bucket for {@link RowExpansion#EXPANDED} and none for {@link
   * RowExpansion#COLLAPSED}. {@link RowExpansion#AUTO} starts Date collapsed (§9.2) — a date range
   * is not a filter, so there is never "exactly one node selected" to expand.
   */
  private static List<DateBucket> expandedDateBuckets(
      RowExpansion expansion, Set<String> explicitOverride, List<DateBucket> buckets) {
    if (explicitOverride != null) {
      return buckets.stream().filter(b -> explicitOverride.contains(b.key())).toList();
    }
    return expansion == RowExpansion.EXPANDED ? buckets : List.of();
  }

  private static List<DateBucket> bucketsFor(
      ReportSpec spec, RangeResolver.ResolvedRange resolved) {
    return DateBucket.bucketsBetween(
        spec.dateLadder().bucketGranularity(), resolved.start(), resolved.end());
  }

  private static Set<String> keysOf(List<DateBucket> buckets) {
    return buckets.stream().map(DateBucket::key).collect(Collectors.toSet());
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
    return effectiveExpandedKeys(spec, explicitOverride, LocalDate.now());
  }

  /**
   * {@link #effectiveExpandedKeys(ReportSpec, Set)} with an injectable "today" — with Date on rows
   * the candidates are the range's own buckets, which depend on it.
   */
  Set<String> effectiveExpandedKeys(
      ReportSpec spec, Set<String> explicitOverride, LocalDate today) {
    AxisPlan axes = planAxes(spec);
    if (axes.dateOnRows()) {
      List<DateBucket> buckets = bucketsFor(spec, RangeResolver.resolve(spec.range(), today));
      return keysOf(expandedDateBuckets(RowExpansion.AUTO, explicitOverride, buckets));
    }
    Map<String, TopLevelNode> candidatesByKey =
        axisCandidates.candidatesFor(axes.nonDateDim(), spec);
    return expandedOuterKeys(
        RowExpansion.AUTO, explicitOverride, axes.nonDateDim(), spec, candidatesByKey);
  }

  private AxisPlan planAxes(ReportSpec spec) {
    Dimension rowDim = rowSlotDimension(spec);
    Dimension colDim = spec.columns().isEmpty() ? null : spec.columns().get(0);
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

  /**
   * Why the engine refuses to render {@code spec} at all, or {@code null} when it renders — decided
   * from the spec alone, before any query. A refusal renders as an empty grid carrying this message
   * (issue 18) rather than an exception, so a page, a Frame or a hand-typed URL shows the reason in
   * place of the report instead of the generic error toast. The settings strip keeps each of these
   * combinations unenterable ({@link ReportSettingsView}); this is the safety net.
   *
   * <p>Refused: Date on both axes; two different non-Date dimensions across rows and columns (stage
   * a's cross-axis cap, see this class's javadoc); and a closing balance with a {@link
   * Dimension#isBalanceless()} dimension (Tag, Payee) in either slot.
   */
  private static String refusal(ReportSpec spec, AxisPlan axes) {
    if (axes.dateOnRows() && axes.dateOnColumns()) {
      return "Date cannot be on both rows and columns.";
    }
    boolean rowIsNonDate = axes.rowDim() != null && !axes.dateOnRows();
    boolean colIsNonDate = axes.colDim() != null && !axes.dateOnColumns();
    if (rowIsNonDate && colIsNonDate) {
      return "Rows and columns cannot carry two different dimensions other than Date — put the"
          + " second one in the nested slot of the same axis instead.";
    }
    if (spec.hasClosingBalance()) {
      String balanceless = balancelessReason(axes.nonDateDim());
      return balanceless != null ? balanceless : balancelessReason(axes.innerDim());
    }
    return null;
  }

  private static String balancelessReason(Dimension dimension) {
    if (dimension == null || !dimension.isBalanceless()) {
      return null;
    }
    return dimension == Dimension.TAG
        ? "A tag has no closing balance (it is not an account) — pick a turnover measure, or a"
            + " dimension other than Tag."
        : "A payee has no closing balance (it is not an account) — pick a turnover measure, or a"
            + " dimension other than Payee.";
  }

  private static ReportGrid refusedGrid(String refusal, RangeResolver.ResolvedRange resolved) {
    return new ReportGrid(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Cell.BLANK,
        resolved.start(),
        resolved.end(),
        refusal);
  }

  private String requireBaseCurrency() {
    return settingsService
        .baseCurrency()
        .orElseThrow(
            () -> new IllegalStateException("Base currency must be set before reporting."));
  }
}
