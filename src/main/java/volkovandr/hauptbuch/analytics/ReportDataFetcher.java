package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;

/**
 * The {@link ReportQueryRepository} calls that fetch a Report's raw turnover/closing-balance data
 * ({@link GridData}); the row/column candidates that data fills are {@link AxisCandidates}' job.
 * Split out from {@link ReportEngine} so that class stays focused on validating and orchestrating,
 * not on which repository method a dimension maps to.
 */
// CouplingBetweenObjects: this class's whole job is dispatching every Dimension in the catalogue
// (reporting.md §4) to its own ReportQueryRepository method — one dimension, one query shape, no
// dimension shares another's. That is naturally a lot of distinct types on the parameter/return
// list of a class this shape, not entangled behavioural coupling; splitting it up would just move
// the same dispatch into another class with the same list of types. Suppressing here rather than
// forcing a seam the domain doesn't actually have (mirrors ReportGridBuilder's own suppression).
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
class ReportDataFetcher {

  private static final String TOTAL_KEY = "total";

  private final ReportQueryRepository queryRepository;

  ReportDataFetcher(ReportQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  /**
   * Every raw turnover/closing-balance row a Report's measures need — including, when {@code
   * expandedOuterKeys} is non-empty, each expanded node's child rows (reporting.md §3, §9), merged
   * straight into the same per-leg/per-bucket lists the outer-level rows already populate. {@link
   * CellValuation} needs no change to read them: a child row's {@code dimensionKey} is already
   * {@code "<outerKey>|<innerKey>"}, matching the composite key {@link
   * ReportGridBuilder#frontierNodes} gave that row's {@link AxisNode}.
   */
  GridData fetchGridData(
      ReportSpec spec,
      AxisPlan axes,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      List<DateBucket> buckets,
      LocalDate today,
      String baseCurrency,
      Set<String> expandedOuterKeys) {
    return fetchGridData(
        spec,
        axes,
        types,
        resolved,
        buckets,
        today,
        baseCurrency,
        expandedOuterKeys,
        spec.dateLadder().bucketGranularity());
  }

  /**
   * {@link #fetchGridData(ReportSpec, AxisPlan, List, RangeResolver.ResolvedRange, List, LocalDate,
   * String, Set)} at an explicit {@code granularity} rather than the ladder's own rung — {@link
   * DateGranularity#DAY} for one expanded Date row's days (reporting.md §9.1).
   */
  GridData fetchGridData(
      ReportSpec spec,
      AxisPlan axes,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      List<DateBucket> buckets,
      LocalDate today,
      String baseCurrency,
      Set<String> expandedOuterKeys,
      DateGranularity granularity) {
    QueryConstraints constraints =
        PromotedNodes.constraints(spec.filters(), spec, axes.nonDateDim(), axes.innerDim());
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg =
        fetchTurnover(
            spec, axes.nonDateDim(), types, resolved, baseCurrency, granularity, constraints);
    if (!expandedOuterKeys.isEmpty()) {
      mergeChildTurnover(
          spec,
          axes.nonDateDim(),
          axes.innerDim(),
          types,
          resolved,
          baseCurrency,
          granularity,
          expandedOuterKeys,
          turnoverByLeg);
    }

    Map<String, List<RawBalanceCell>> balanceByBucketKey = new LinkedHashMap<>();
    Map<String, LocalDate> asOfByBucketKey = new LinkedHashMap<>();
    if (spec.hasClosingBalance()) {
      boolean hasDateAxis = axes.dateOnRows() || axes.dateOnColumns();
      List<DateBucket> balanceBuckets = hasDateAxis ? buckets : List.of();
      fetchClosingBalance(
          axes.nonDateDim(),
          types,
          spec.scope(),
          balanceBuckets,
          resolved,
          today,
          constraints,
          balanceByBucketKey,
          asOfByBucketKey);
      if (!expandedOuterKeys.isEmpty()) {
        mergeChildClosingBalance(
            spec,
            axes.nonDateDim(),
            axes.innerDim(),
            types,
            spec.scope(),
            balanceBuckets,
            expandedOuterKeys,
            balanceByBucketKey,
            asOfByBucketKey);
      }
    }
    return new GridData(turnoverByLeg, balanceByBucketKey, asOfByBucketKey);
  }

  /**
   * One expanded outer node's child turnover, per distinct {@link Leg} the spec's measures need,
   * merged into {@code byLeg} (whose every key {@link #fetchTurnover} has already populated).
   * {@code innerDim} set: {@link #queryTurnover} for {@code innerDim}, exactly as the outer level
   * uses it, with one extra synthetic subtree filter scoping it to {@code outerKey} (§3's "reuse
   * the existing filter-predicate compiler", no combinatorial per-dimension-pair SQL). {@code
   * innerDim} {@code null}: {@code outerDim}'s own {@code parentId}-seeded child query (§9.1) — no
   * synthetic filter needed, the query's own recursive CTE already scopes it structurally.
   */
  private void mergeChildTurnover(
      ReportSpec spec,
      Dimension outerDim,
      Dimension innerDim,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      String baseCurrency,
      DateGranularity granularity,
      Set<String> expandedOuterKeys,
      Map<Leg, List<RawTurnoverCell>> byLeg) {
    Set<Leg> legs = EnumSet.noneOf(Leg.class);
    for (Measure measure : spec.measures()) {
      Leg leg = legFor(measure);
      if (leg != null) {
        legs.add(leg);
      }
    }
    makeEveryListMutable(byLeg, legs);
    for (String outerKey : expandedOuterKeys) {
      for (Leg leg : legs) {
        List<RawTurnoverCell> childRows =
            childTurnover(
                outerDim,
                innerDim,
                outerKey,
                types,
                resolved,
                baseCurrency,
                granularity,
                leg.name(),
                spec);
        byLeg
            .get(leg)
            .addAll(
                childRows.stream()
                    .map(c -> c.withDimensionKey(outerKey + "|" + c.dimensionKey()))
                    .toList());
      }
    }
  }

  // AvoidInstantiatingObjectsInLoops: the one `new ArrayList<>(existing)` per key turns a
  // possibly-shared list mutable exactly once (bounded by the handful of legs/buckets a spec ever
  // needs), not a per-iteration allocation the rule means to catch.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private static <K, V> void makeEveryListMutable(Map<K, List<V>> byKey, Set<K> keys) {
    for (K key : keys) {
      byKey.computeIfPresent(key, (k, existing) -> new ArrayList<>(existing));
    }
  }

  private List<RawTurnoverCell> childTurnover(
      Dimension outerDim,
      Dimension innerDim,
      String outerKey,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      String baseCurrency,
      DateGranularity granularity,
      String legName,
      ReportSpec spec) {
    boolean includeClosed = spec.scope().includeClosedAccounts();
    boolean includePending = spec.scope().includePendingReview();
    if (innerDim != null) {
      QueryConstraints childConstraints = withSyntheticFilter(spec, outerDim, innerDim, outerKey);
      return queryTurnover(
          innerDim,
          types,
          resolved,
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          childConstraints);
    }
    QueryConstraints constraints = PromotedNodes.constraints(spec.filters(), spec, outerDim, null);
    long parentId = AxisNode.realId(outerKey);
    if (outerDim == Dimension.TAG) {
      return queryRepository.childTagTurnover(
          parentId,
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    return ScopeDimensionMismatch.ownAccountTypes(outerDim, types)
        .map(
            ownTypes ->
                queryRepository.childAccountTurnover(
                    parentId,
                    ownTypes,
                    resolved.start(),
                    resolved.end(),
                    baseCurrency,
                    legName,
                    includeClosed,
                    includePending,
                    granularity,
                    constraints))
        .orElse(List.of());
  }

  /**
   * Mirrors {@link #mergeChildTurnover} for closing balance, reusing {@link #closingBalanceAt} (the
   * cross-dimension case) or {@link ReportQueryRepository#childAccountClosingBalance} (the
   * same-dimension case — {@code outerDim} is never {@link Dimension#TAG} here, since a closing-
   * balance measure with Tag anywhere in the nesting is already rejected at spec-validation time,
   * {@code ReportEngine#rejectIfBalanceless}) per bucket (or the single {@code "total"} bucket with
   * no date axis).
   */
  private void mergeChildClosingBalance(
      ReportSpec spec,
      Dimension outerDim,
      Dimension innerDim,
      List<String> types,
      Scope scope,
      List<DateBucket> dateAxisBuckets,
      Set<String> expandedOuterKeys,
      Map<String, List<RawBalanceCell>> balanceByBucketKey,
      Map<String, LocalDate> asOfByBucketKey) {
    List<String> bucketKeys =
        dateAxisBuckets.isEmpty()
            ? List.of(TOTAL_KEY)
            : dateAxisBuckets.stream().map(DateBucket::key).toList();
    makeEveryListMutable(balanceByBucketKey, Set.copyOf(bucketKeys));
    for (String outerKey : expandedOuterKeys) {
      for (String bucketKey : bucketKeys) {
        LocalDate asOf = asOfByBucketKey.get(bucketKey);
        List<RawBalanceCell> childRows =
            childClosingBalance(outerDim, innerDim, outerKey, types, scope, asOf, spec);
        balanceByBucketKey
            .get(bucketKey)
            .addAll(
                childRows.stream()
                    .map(c -> c.withDimensionKey(outerKey + "|" + c.dimensionKey()))
                    .toList());
      }
    }
  }

  private List<RawBalanceCell> childClosingBalance(
      Dimension outerDim,
      Dimension innerDim,
      String outerKey,
      List<String> types,
      Scope scope,
      LocalDate asOf,
      ReportSpec spec) {
    if (innerDim != null) {
      QueryConstraints childConstraints = withSyntheticFilter(spec, outerDim, innerDim, outerKey);
      return closingBalanceAt(innerDim, types, scope, asOf, childConstraints);
    }
    QueryConstraints constraints = PromotedNodes.constraints(spec.filters(), spec, outerDim, null);
    long parentId = AxisNode.realId(outerKey);
    return ScopeDimensionMismatch.ownAccountTypes(outerDim, types)
        .map(
            ownTypes ->
                queryRepository.childAccountClosingBalance(
                    parentId,
                    ownTypes,
                    asOf,
                    scope.includeClosedAccounts(),
                    scope.includePendingReview(),
                    constraints))
        .orElse(List.of());
  }

  private static Leg legFor(Measure measure) {
    return switch (measure.kind()) {
      case TURNOVER -> measure.leg();
      case COUNT_POSTINGS, COUNT_TRANSACTIONS -> Leg.NET;
      case CLOSING_BALANCE -> null;
    };
  }

  /**
   * The engine's own default filter reading (reporting.md §6.2's table) for a synthetic nesting
   * filter — never user-authored, so it always takes the field's documented default rather than
   * offering a choice: {@link Dimension#CATEGORY} is posting-level; {@link Dimension#ACCOUNT} and
   * {@link Dimension#TAG} are transaction-level. Only a hierarchical dimension can be the outer
   * half of stage e's nesting (§9.1), so no other dimension ever reaches this.
   */
  private static ReportFilter syntheticSubtreeFilter(Dimension dimension, String rawKey) {
    FilterField field = AutoExpansion.toFilterField(dimension);
    FilterLevel level =
        dimension == Dimension.CATEGORY ? FilterLevel.POSTING : FilterLevel.TRANSACTION;
    return new ReportFilter(field, level, FilterOperator.IS_ONE_OF, List.of(rawKey));
  }

  private static QueryConstraints withSyntheticFilter(
      ReportSpec spec, Dimension outerDim, Dimension innerDim, String rawKey) {
    List<ReportFilter> combined = new ArrayList<>(spec.filters());
    combined.add(syntheticSubtreeFilter(outerDim, rawKey));
    return PromotedNodes.constraints(combined, spec, outerDim, innerDim);
  }

  /**
   * Every {@link Leg} a {@link MeasureKind#TURNOVER} measure needs, plus (under the {@link Leg#NET}
   * key) the same NET-leg data a count measure reads its posting/transaction counts from (§5.5) —
   * counts have no leg of their own, so they always share the NET fetch.
   */
  private Map<Leg, List<RawTurnoverCell>> fetchTurnover(
      ReportSpec spec,
      Dimension nonDateDim,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      String baseCurrency,
      DateGranularity granularity,
      QueryConstraints constraints) {
    Map<Leg, List<RawTurnoverCell>> byLeg = new EnumMap<>(Leg.class);
    for (Measure measure : spec.measures()) {
      Leg leg = legFor(measure);
      if (leg == null) {
        continue;
      }
      byLeg.computeIfAbsent(
          leg,
          l ->
              queryTurnover(
                  nonDateDim,
                  types,
                  resolved,
                  baseCurrency,
                  l.name(),
                  spec.scope().includeClosedAccounts(),
                  spec.scope().includePendingReview(),
                  granularity,
                  constraints));
    }
    return byLeg;
  }

  private List<RawTurnoverCell> queryTurnover(
      Dimension nonDateDim,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      String baseCurrency,
      String legName,
      boolean includeClosed,
      boolean includePending,
      DateGranularity granularity,
      QueryConstraints constraints) {
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      return ScopeDimensionMismatch.ownAccountTypes(nonDateDim, types)
          .map(
              ownTypes ->
                  queryRepository.accountTreeTurnover(
                      ownTypes,
                      resolved.start(),
                      resolved.end(),
                      baseCurrency,
                      legName,
                      includeClosed,
                      includePending,
                      granularity,
                      constraints))
          .orElse(List.of());
    }
    if (nonDateDim == Dimension.TAG) {
      return queryRepository.tagTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    if (nonDateDim == Dimension.PAYEE) {
      return queryRepository.payeeTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    if (nonDateDim == Dimension.PERSON) {
      return queryRepository.personTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    if (nonDateDim == Dimension.CURRENCY) {
      return queryRepository.currencyTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    if (nonDateDim == Dimension.ACCOUNT_TYPE) {
      return queryRepository.accountTypeTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          granularity,
          constraints);
    }
    return queryRepository.totalTurnover(
        types,
        resolved.start(),
        resolved.end(),
        baseCurrency,
        legName,
        includeClosed,
        includePending,
        granularity,
        constraints);
  }

  private void fetchClosingBalance(
      Dimension nonDateDim,
      List<String> types,
      Scope scope,
      List<DateBucket> dateAxisBuckets,
      RangeResolver.ResolvedRange resolved,
      LocalDate today,
      QueryConstraints constraints,
      Map<String, List<RawBalanceCell>> balanceByBucketKey,
      Map<String, LocalDate> asOfByBucketKey) {
    if (dateAxisBuckets.isEmpty()) {
      LocalDate asOf = clampToToday(resolved.end(), today);
      asOfByBucketKey.put(TOTAL_KEY, asOf);
      balanceByBucketKey.put(
          TOTAL_KEY, closingBalanceAt(nonDateDim, types, scope, asOf, constraints));
      return;
    }
    for (DateBucket bucket : dateAxisBuckets) {
      // Use the bucket's own effective end — clipped to the report's actual range, not the full
      // calendar month — then clamp to today (reporting.md §8.2).
      LocalDate asOf = clampToToday(bucket.effectiveEnd(), today);
      asOfByBucketKey.put(bucket.key(), asOf);
      balanceByBucketKey.put(
          bucket.key(), closingBalanceAt(nonDateDim, types, scope, asOf, constraints));
    }
  }

  private List<RawBalanceCell> closingBalanceAt(
      Dimension nonDateDim,
      List<String> types,
      Scope scope,
      LocalDate asOf,
      QueryConstraints constraints) {
    boolean includeClosed = scope.includeClosedAccounts();
    boolean includePending = scope.includePendingReview();
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      return ScopeDimensionMismatch.ownAccountTypes(nonDateDim, types)
          .map(
              ownTypes ->
                  queryRepository.accountTreeClosingBalance(
                      ownTypes, asOf, includeClosed, includePending, constraints))
          .orElse(List.of());
    }
    if (nonDateDim == Dimension.PERSON) {
      return queryRepository.personClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
    }
    if (nonDateDim == Dimension.CURRENCY) {
      return queryRepository.currencyClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
    }
    if (nonDateDim == Dimension.ACCOUNT_TYPE) {
      return queryRepository.accountTypeClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
    }
    return queryRepository.totalClosingBalance(
        types, asOf, includeClosed, includePending, constraints);
  }

  private static LocalDate clampToToday(LocalDate date, LocalDate today) {
    return date.isAfter(today) ? today : date;
  }
}
