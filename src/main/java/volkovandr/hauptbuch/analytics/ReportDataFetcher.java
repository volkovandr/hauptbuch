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
import volkovandr.hauptbuch.analytics.repository.NodeKey;
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
   * Every raw turnover/closing-balance row a Report's measures need over {@code resolved}, bucketed
   * at {@code granularity} — the ladder's own rung for the whole range, {@link DateGranularity#DAY}
   * for one expanded Date row's days (reporting.md §9.1). Includes, when the context's expanded
   * keys are non-empty, each expanded node's child rows (§3, §9), merged straight into the same
   * per-leg/per-bucket lists the outer-level rows already populate. {@link CellValuation} needs no
   * change to read them: a child row's {@code dimensionKey} is already {@code
   * "<outerKey>|<innerKey>"}, matching the composite key {@link ReportGridBuilder#frontierNodes}
   * gave that row's {@link AxisNode}.
   */
  GridData fetchGridData(
      FetchContext context,
      RangeResolver.ResolvedRange resolved,
      List<DateBucket> buckets,
      DateGranularity granularity) {
    AxisPlan axes = context.axes();
    QueryConstraints constraints =
        PromotedNodes.constraints(
            context.spec().filters(), context.spec(), axes.nonDateDim(), axes.innerDim());
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg =
        fetchTurnover(context, resolved, granularity, constraints);
    if (!context.expandedKeys().isEmpty()) {
      mergeChildTurnover(context, resolved, granularity, turnoverByLeg);
    }

    Map<String, List<RawBalanceCell>> balanceByBucketKey = new LinkedHashMap<>();
    Map<String, LocalDate> asOfByBucketKey = new LinkedHashMap<>();
    if (context.spec().hasClosingBalance()) {
      boolean hasDateAxis = axes.dateOnRows() || axes.dateOnColumns();
      List<DateBucket> balanceBuckets = hasDateAxis ? buckets : List.of();
      fetchClosingBalance(
          context, balanceBuckets, resolved, constraints, balanceByBucketKey, asOfByBucketKey);
      if (!context.expandedKeys().isEmpty()) {
        mergeChildClosingBalance(context, balanceBuckets, balanceByBucketKey, asOfByBucketKey);
      }
    }
    return new GridData(turnoverByLeg, balanceByBucketKey, asOfByBucketKey);
  }

  /**
   * One expanded outer node's child turnover, per distinct {@link Leg} the spec's measures need,
   * merged into {@code byLeg} (whose every key {@link #fetchTurnover} has already populated). An
   * inner dimension set: {@link #queryTurnover} for it, exactly as the outer level uses it, with
   * one extra synthetic subtree filter scoping it to the outer key (§3's "reuse the existing
   * filter-predicate compiler", no combinatorial per-dimension-pair SQL). No inner dimension: the
   * outer dimension's own {@code parentId}-seeded child query (§9.1) — no synthetic filter needed,
   * the query's own recursive CTE already scopes it structurally.
   */
  private void mergeChildTurnover(
      FetchContext context,
      RangeResolver.ResolvedRange resolved,
      DateGranularity granularity,
      Map<Leg, List<RawTurnoverCell>> byLeg) {
    Set<Leg> legs = EnumSet.noneOf(Leg.class);
    for (Measure measure : context.spec().measures()) {
      Leg leg = legFor(measure);
      if (leg != null) {
        legs.add(leg);
      }
    }
    makeEveryListMutable(byLeg, legs);
    for (String outerKey : context.expandedKeys()) {
      for (Leg leg : legs) {
        List<RawTurnoverCell> childRows =
            childTurnover(context, outerKey, resolved, granularity, leg.name());
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
      FetchContext context,
      String outerKey,
      RangeResolver.ResolvedRange resolved,
      DateGranularity granularity,
      String legName) {
    Dimension outerDim = context.axes().nonDateDim();
    Dimension innerDim = context.axes().innerDim();
    if (innerDim != null) {
      return queryTurnover(
          context,
          innerDim,
          resolved,
          granularity,
          legName,
          withSyntheticFilter(context, outerKey));
    }
    QueryConstraints constraints =
        PromotedNodes.constraints(context.spec().filters(), context.spec(), outerDim, null);
    NodeKey parent = NodeKey.ofLastSegment(outerKey);
    if (outerDim == Dimension.TAG) {
      return queryRepository.childTagTurnover(
          parent.id(),
          context.types(),
          resolved.start(),
          resolved.end(),
          context.baseCurrency(),
          legName,
          context.includeClosedAccounts(),
          context.includePendingReview(),
          granularity,
          constraints);
    }
    return ScopeDimensionMismatch.ownAccountTypes(outerDim, context.types())
        .map(
            ownTypes ->
                childAccountTreeTurnover(
                    context, parent, ownTypes, resolved, granularity, legName, constraints))
        .orElse(List.of());
  }

  /**
   * One expanded account-tree node's children's turnover: a real node's direct children, the
   * "Personal debts" node's people, or a person's debt leaves (reporting issue 06).
   */
  private List<RawTurnoverCell> childAccountTreeTurnover(
      FetchContext context,
      NodeKey parent,
      List<String> ownTypes,
      RangeResolver.ResolvedRange resolved,
      DateGranularity granularity,
      String legName,
      QueryConstraints constraints) {
    String baseCurrency = context.baseCurrency();
    boolean includeClosed = context.includeClosedAccounts();
    boolean includePending = context.includePendingReview();
    return switch (parent.kind()) {
      case PERSONAL_DEBTS ->
          queryRepository.debtPeopleTurnover(
              ownTypes,
              resolved.start(),
              resolved.end(),
              baseCurrency,
              legName,
              includeClosed,
              includePending,
              granularity,
              constraints);
      case PERSON ->
          queryRepository.debtLeafTurnover(
              parent.id(),
              ownTypes,
              resolved.start(),
              resolved.end(),
              baseCurrency,
              legName,
              includeClosed,
              includePending,
              granularity,
              constraints);
      case NODE ->
          queryRepository.childAccountTurnover(
              parent.id(),
              ownTypes,
              resolved.start(),
              resolved.end(),
              baseCurrency,
              legName,
              includeClosed,
              includePending,
              granularity,
              constraints);
    };
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
      FetchContext context,
      List<DateBucket> dateAxisBuckets,
      Map<String, List<RawBalanceCell>> balanceByBucketKey,
      Map<String, LocalDate> asOfByBucketKey) {
    List<String> bucketKeys =
        dateAxisBuckets.isEmpty()
            ? List.of(TOTAL_KEY)
            : dateAxisBuckets.stream().map(DateBucket::key).toList();
    makeEveryListMutable(balanceByBucketKey, Set.copyOf(bucketKeys));
    for (String outerKey : context.expandedKeys()) {
      for (String bucketKey : bucketKeys) {
        LocalDate asOf = asOfByBucketKey.get(bucketKey);
        List<RawBalanceCell> childRows = childClosingBalance(context, outerKey, asOf);
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
      FetchContext context, String outerKey, LocalDate asOf) {
    Dimension outerDim = context.axes().nonDateDim();
    Dimension innerDim = context.axes().innerDim();
    if (innerDim != null) {
      return closingBalanceAt(context, innerDim, asOf, withSyntheticFilter(context, outerKey));
    }
    QueryConstraints constraints =
        PromotedNodes.constraints(context.spec().filters(), context.spec(), outerDim, null);
    NodeKey parent = NodeKey.ofLastSegment(outerKey);
    boolean includeClosed = context.includeClosedAccounts();
    boolean includePending = context.includePendingReview();
    return ScopeDimensionMismatch.ownAccountTypes(outerDim, context.types())
        .map(
            ownTypes ->
                switch (parent.kind()) {
                  case PERSONAL_DEBTS ->
                      queryRepository.debtPeopleClosingBalance(
                          ownTypes, asOf, includeClosed, includePending, constraints);
                  case PERSON ->
                      queryRepository.debtLeafClosingBalance(
                          parent.id(), ownTypes, asOf, includeClosed, includePending, constraints);
                  case NODE ->
                      queryRepository.childAccountClosingBalance(
                          parent.id(), ownTypes, asOf, includeClosed, includePending, constraints);
                })
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

  /**
   * The spec's filters plus a synthetic subtree filter scoping the inner dimension to {@code
   * outerKey}.
   */
  private static QueryConstraints withSyntheticFilter(FetchContext context, String outerKey) {
    ReportSpec spec = context.spec();
    Dimension outerDim = context.axes().nonDateDim();
    List<ReportFilter> combined = new ArrayList<>(spec.filters());
    combined.add(syntheticSubtreeFilter(outerDim, outerKey));
    return PromotedNodes.constraints(combined, spec, outerDim, context.axes().innerDim());
  }

  /**
   * Every {@link Leg} a {@link MeasureKind#TURNOVER} measure needs, plus (under the {@link Leg#NET}
   * key) the same NET-leg data a count measure reads its posting/transaction counts from (§5.5) —
   * counts have no leg of their own, so they always share the NET fetch.
   */
  private Map<Leg, List<RawTurnoverCell>> fetchTurnover(
      FetchContext context,
      RangeResolver.ResolvedRange resolved,
      DateGranularity granularity,
      QueryConstraints constraints) {
    Map<Leg, List<RawTurnoverCell>> byLeg = new EnumMap<>(Leg.class);
    for (Measure measure : context.spec().measures()) {
      Leg leg = legFor(measure);
      if (leg == null) {
        continue;
      }
      byLeg.computeIfAbsent(
          leg,
          l ->
              queryTurnover(
                  context,
                  context.axes().nonDateDim(),
                  resolved,
                  granularity,
                  l.name(),
                  constraints));
    }
    return byLeg;
  }

  private List<RawTurnoverCell> queryTurnover(
      FetchContext context,
      Dimension dimension,
      RangeResolver.ResolvedRange resolved,
      DateGranularity granularity,
      String legName,
      QueryConstraints constraints) {
    List<String> types = context.types();
    String baseCurrency = context.baseCurrency();
    boolean includeClosed = context.includeClosedAccounts();
    boolean includePending = context.includePendingReview();
    if (dimension == Dimension.CATEGORY || dimension == Dimension.ACCOUNT) {
      return ScopeDimensionMismatch.ownAccountTypes(dimension, types)
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
    if (dimension == Dimension.TAG) {
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
    if (dimension == Dimension.PAYEE) {
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
    if (dimension == Dimension.PERSON) {
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
    if (dimension == Dimension.CURRENCY) {
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
    if (dimension == Dimension.ACCOUNT_TYPE) {
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
      FetchContext context,
      List<DateBucket> dateAxisBuckets,
      RangeResolver.ResolvedRange resolved,
      QueryConstraints constraints,
      Map<String, List<RawBalanceCell>> balanceByBucketKey,
      Map<String, LocalDate> asOfByBucketKey) {
    Dimension nonDateDim = context.axes().nonDateDim();
    if (dateAxisBuckets.isEmpty()) {
      LocalDate asOf = clampToToday(resolved.end(), context.today());
      asOfByBucketKey.put(TOTAL_KEY, asOf);
      balanceByBucketKey.put(TOTAL_KEY, closingBalanceAt(context, nonDateDim, asOf, constraints));
      return;
    }
    for (DateBucket bucket : dateAxisBuckets) {
      // Use the bucket's own effective end — clipped to the report's actual range, not the full
      // calendar month — then clamp to today (reporting.md §8.2).
      LocalDate asOf = clampToToday(bucket.effectiveEnd(), context.today());
      asOfByBucketKey.put(bucket.key(), asOf);
      balanceByBucketKey.put(
          bucket.key(), closingBalanceAt(context, nonDateDim, asOf, constraints));
    }
  }

  private List<RawBalanceCell> closingBalanceAt(
      FetchContext context, Dimension dimension, LocalDate asOf, QueryConstraints constraints) {
    List<String> types = context.types();
    boolean includeClosed = context.includeClosedAccounts();
    boolean includePending = context.includePendingReview();
    if (dimension == Dimension.CATEGORY || dimension == Dimension.ACCOUNT) {
      return ScopeDimensionMismatch.ownAccountTypes(dimension, types)
          .map(
              ownTypes ->
                  queryRepository.accountTreeClosingBalance(
                      ownTypes, asOf, includeClosed, includePending, constraints))
          .orElse(List.of());
    }
    if (dimension == Dimension.PERSON) {
      return queryRepository.personClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
    }
    if (dimension == Dimension.CURRENCY) {
      return queryRepository.currencyClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
    }
    if (dimension == Dimension.ACCOUNT_TYPE) {
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
