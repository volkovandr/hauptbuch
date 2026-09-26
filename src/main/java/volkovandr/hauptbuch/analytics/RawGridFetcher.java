package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.LeafGrainRepository;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;

/**
 * A Report's raw data (reporting.md §13): every hierarchy on the axis fully expanded to its leaves,
 * fetched at leaf grain — one turnover query per leg and one balance query per Date bucket, however
 * deep the trees — and rolled up to the leaves the Report's dimensions name ({@link LeafRollup}).
 * The result is the same {@link GridData} an ordinary render fills, keyed by leaf, so {@link
 * CellValuation} values a raw cell exactly as it values any other.
 */
@Component
class RawGridFetcher {

  private final LeafGrainRepository leafGrainRepository;

  RawGridFetcher(LeafGrainRepository leafGrainRepository) {
    this.leafGrainRepository = leafGrainRepository;
  }

  /**
   * The raw data and the leaves it names.
   *
   * @param data every leaf's groups, keyed as its {@link AxisNode}
   * @param leaves the axis's leaves with data, sorted by label
   */
  record RawGrid(GridData data, List<AxisNode> leaves) {}

  /** {@code spec}'s raw data over {@code resolved}, bucketed as {@code buckets}. */
  RawGrid fetch(
      ReportSpec spec,
      AxisPlan axes,
      LocalDate today,
      String baseCurrency,
      RangeResolver.ResolvedRange resolved,
      List<DateBucket> buckets) {
    Optional<List<String>> types = queryTypes(axes, List.copyOf(spec.scope().accountTypes()));
    if (types.isEmpty()) {
      return new RawGrid(new GridData(Map.of(), Map.of(), Map.of()), List.of());
    }
    LeafRollup rollup =
        new LeafRollup(
            axes,
            new LeafLabels(
                leafGrainRepository.accounts(),
                leafGrainRepository.tags(),
                leafGrainRepository.payees(),
                tickedTagKeys(spec)));
    QueryConstraints constraints = new QueryConstraints(spec.filters());
    Scope scope = spec.scope();
    boolean byTag = axes.nonDateDim() == Dimension.TAG || axes.innerDim() == Dimension.TAG;
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg = new EnumMap<>(Leg.class);
    spec.measures().stream()
        .map(ReportDataFetcher::legFor)
        .filter(leg -> leg != null && !turnoverByLeg.containsKey(leg))
        .forEach(
            leg ->
                turnoverByLeg.put(
                    leg,
                    rollup.turnover(
                        leafGrainRepository.turnover(
                            new LeafGrainRepository.TurnoverQuery(
                                types.get(),
                                resolved.start(),
                                resolved.end(),
                                spec.dateLadder().bucketGranularity(),
                                baseCurrency,
                                leg.name(),
                                scope.includeClosedAccounts(),
                                scope.includePendingReview()),
                            byTag,
                            constraints))));
    Map<String, List<RawBalanceCell>> balanceByBucketKey = new LinkedHashMap<>();
    Map<String, LocalDate> asOfByBucketKey = new LinkedHashMap<>();
    if (spec.hasClosingBalance()) {
      asOfByBucketKey.putAll(balanceAsOfs(axes, resolved, buckets, today));
      asOfByBucketKey.forEach(
          (bucketKey, asOf) ->
              balanceByBucketKey.put(
                  bucketKey,
                  rollup.balance(
                      leafGrainRepository.closingBalance(
                          types.get(),
                          asOf,
                          scope.includeClosedAccounts(),
                          scope.includePendingReview(),
                          constraints))));
    }
    return new RawGrid(
        new GridData(turnoverByLeg, balanceByBucketKey, asOfByBucketKey),
        sortedByLabel(rollup.leafLabels()));
  }

  private static List<AxisNode> sortedByLabel(Map<String, String> labelsByLeafKey) {
    return labelsByLeafKey.entrySet().stream()
        .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
        .map(entry -> new AxisNode(entry.getKey(), entry.getValue()))
        .toList();
  }

  /**
   * The tags a Tag filter on amounts booked ticks (§6.3): the Report shows only them, so raw keeps
   * a posting's other tags out. Under "transactions touching" every tag the postings carry shows.
   */
  private static List<String> tickedTagKeys(ReportSpec spec) {
    return PromotedNodes.ownFilter(Dimension.TAG, spec)
        .filter(filter -> filter.level() == FilterLevel.POSTING)
        .map(ReportFilter::values)
        .orElse(List.of());
  }

  /**
   * The account types the leaf queries read: a Category or Account dimension's own types in scope
   * (reporting.md §4), as its tree queries walk them; empty when the scope has none of them.
   */
  private static Optional<List<String>> queryTypes(AxisPlan axes, List<String> scopeTypes) {
    Optional<List<String>> types = Optional.of(scopeTypes);
    for (Dimension dimension : new Dimension[] {axes.nonDateDim(), axes.innerDim()}) {
      if (dimension == Dimension.CATEGORY || dimension == Dimension.ACCOUNT) {
        types = types.flatMap(t -> ScopeDimensionMismatch.ownAccountTypes(dimension, t));
      }
    }
    return types;
  }

  /**
   * Each balance bucket's as-of date: the bucket's clipped end, or the range's end with no Date
   * axis, never later than today (reporting.md §8.2).
   */
  private static Map<String, LocalDate> balanceAsOfs(
      AxisPlan axes,
      RangeResolver.ResolvedRange resolved,
      List<DateBucket> buckets,
      LocalDate today) {
    Map<String, LocalDate> asOfs = new LinkedHashMap<>();
    if (!axes.dateOnRows() && !axes.dateOnColumns()) {
      asOfs.put(AxisNode.TOTAL_KEY, ReportDataFetcher.clampToToday(resolved.end(), today));
      return asOfs;
    }
    buckets.forEach(
        b -> asOfs.put(b.key(), ReportDataFetcher.clampToToday(b.effectiveEnd(), today)));
    return asOfs;
  }
}
