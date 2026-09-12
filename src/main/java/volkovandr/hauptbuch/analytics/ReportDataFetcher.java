package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * The {@link ReportQueryRepository} calls a Report needs: the hierarchical dimension's row/column
 * candidates (§7.3 — including ones with no activity, so an all-blank row can be suppressed rather
 * than never listed) and the raw turnover/closing-balance data ({@link GridData}). Split out from
 * {@link ReportEngine} so that class stays focused on validating and orchestrating, not on which
 * repository method a dimension maps to.
 */
@Component
class ReportDataFetcher {

  private static final String TOTAL_KEY = "total";

  private final ReportQueryRepository queryRepository;

  ReportDataFetcher(ReportQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  /** The row/column candidates for a hierarchical dimension, keyed by their stable key. */
  Map<String, TopLevelNode> candidatesFor(Dimension nonDateDim, List<String> types, Scope scope) {
    List<TopLevelNode> nodes;
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      nodes = queryRepository.topLevelAccounts(types, scope.includeClosedAccounts());
    } else if (nonDateDim == Dimension.TAG) {
      nodes = queryRepository.topLevelTags();
    } else {
      nodes = List.of();
    }
    return nodes.stream()
        .collect(Collectors.toMap(TopLevelNode::key, n -> n, (a, b) -> a, LinkedHashMap::new));
  }

  /** Every raw turnover/closing-balance row a Report's measures need. */
  GridData fetchGridData(
      ReportSpec spec,
      AxisPlan axes,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      List<MonthBucket> buckets,
      LocalDate today,
      String baseCurrency) {
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg =
        fetchTurnover(spec, axes.nonDateDim(), types, resolved, baseCurrency);

    Map<String, List<RawBalanceCell>> balanceByBucketKey = new LinkedHashMap<>();
    Map<String, LocalDate> asOfByBucketKey = new LinkedHashMap<>();
    boolean anyClosingBalance =
        spec.measures().stream().anyMatch(m -> m.kind() == MeasureKind.CLOSING_BALANCE);
    if (anyClosingBalance) {
      boolean hasDateAxis = axes.dateOnRows() || axes.dateOnColumns();
      fetchClosingBalance(
          axes.nonDateDim(),
          types,
          spec.scope(),
          hasDateAxis ? buckets : List.of(),
          resolved,
          today,
          balanceByBucketKey,
          asOfByBucketKey);
    }
    return new GridData(turnoverByLeg, balanceByBucketKey, asOfByBucketKey);
  }

  private Map<Leg, List<RawTurnoverCell>> fetchTurnover(
      ReportSpec spec,
      Dimension nonDateDim,
      List<String> types,
      RangeResolver.ResolvedRange resolved,
      String baseCurrency) {
    Map<Leg, List<RawTurnoverCell>> byLeg = new EnumMap<>(Leg.class);
    for (Measure measure : spec.measures()) {
      if (measure.kind() != MeasureKind.TURNOVER) {
        continue;
      }
      byLeg.computeIfAbsent(
          measure.leg(),
          leg ->
              queryTurnover(
                  nonDateDim,
                  types,
                  resolved,
                  baseCurrency,
                  leg.name(),
                  spec.scope().includeClosedAccounts(),
                  spec.scope().includePendingReview()));
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
      boolean includePending) {
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      return queryRepository.accountTreeTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending);
    }
    if (nonDateDim == Dimension.TAG) {
      return queryRepository.tagTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending);
    }
    return queryRepository.totalTurnover(
        types,
        resolved.start(),
        resolved.end(),
        baseCurrency,
        legName,
        includeClosed,
        includePending);
  }

  private void fetchClosingBalance(
      Dimension nonDateDim,
      List<String> types,
      Scope scope,
      List<MonthBucket> dateAxisBuckets,
      RangeResolver.ResolvedRange resolved,
      LocalDate today,
      Map<String, List<RawBalanceCell>> balanceByBucketKey,
      Map<String, LocalDate> asOfByBucketKey) {
    if (dateAxisBuckets.isEmpty()) {
      LocalDate asOf = clampToToday(resolved.end(), today);
      asOfByBucketKey.put(TOTAL_KEY, asOf);
      balanceByBucketKey.put(TOTAL_KEY, closingBalanceAt(nonDateDim, types, scope, asOf));
      return;
    }
    for (MonthBucket bucket : dateAxisBuckets) {
      // Use the bucket's own effective end — clipped to the report's actual range, not the full
      // calendar month — then clamp to today (reporting.md §8.2).
      LocalDate asOf = clampToToday(bucket.effectiveEnd(), today);
      asOfByBucketKey.put(bucket.key(), asOf);
      balanceByBucketKey.put(bucket.key(), closingBalanceAt(nonDateDim, types, scope, asOf));
    }
  }

  private List<RawBalanceCell> closingBalanceAt(
      Dimension nonDateDim, List<String> types, Scope scope, LocalDate asOf) {
    boolean includeClosed = scope.includeClosedAccounts();
    boolean includePending = scope.includePendingReview();
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      return queryRepository.accountTreeClosingBalance(types, asOf, includeClosed, includePending);
    }
    return queryRepository.totalClosingBalance(types, asOf, includeClosed, includePending);
  }

  private static LocalDate clampToToday(LocalDate date, LocalDate today) {
    return date.isAfter(today) ? today : date;
  }
}
