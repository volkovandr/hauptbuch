package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * The {@link ReportQueryRepository} calls a Report needs: the row/column candidates (§7.3 —
 * including ones with no activity, so an all-blank row can be suppressed rather than never listed)
 * and the raw turnover/closing-balance data ({@link GridData}). Split out from {@link ReportEngine}
 * so that class stays focused on validating and orchestrating, not on which repository method a
 * dimension maps to.
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
   * The row/column candidates for a dimension, keyed by their stable key. {@link
   * Dimension#ACCOUNT_TYPE} needs no query — its candidates are exactly {@code types} themselves,
   * capitalized for display.
   */
  Map<String, TopLevelNode> candidatesFor(Dimension nonDateDim, List<String> types, Scope scope) {
    List<TopLevelNode> nodes;
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      nodes = queryRepository.topLevelAccounts(types, scope.includeClosedAccounts());
    } else if (nonDateDim == Dimension.TAG) {
      nodes = queryRepository.topLevelTags();
    } else if (nonDateDim == Dimension.PAYEE) {
      nodes = queryRepository.payeeCandidates();
    } else if (nonDateDim == Dimension.PERSON) {
      nodes = queryRepository.personCandidates();
    } else if (nonDateDim == Dimension.CURRENCY) {
      nodes = queryRepository.currencyCandidates();
    } else if (nonDateDim == Dimension.ACCOUNT_TYPE) {
      nodes = accountTypeCandidates(types);
    } else {
      nodes = List.of();
    }
    return nodes.stream()
        .collect(Collectors.toMap(TopLevelNode::key, n -> n, (a, b) -> a, LinkedHashMap::new));
  }

  private static List<TopLevelNode> accountTypeCandidates(List<String> types) {
    List<TopLevelNode> nodes = new ArrayList<>();
    for (String type : types) {
      nodes.add(new TopLevelNode(type, capitalize(type), type));
    }
    return nodes;
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
    QueryConstraints constraints =
        new QueryConstraints(spec.scope().accountSubtreeRoots(), spec.filters());
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg =
        fetchTurnover(spec, axes.nonDateDim(), types, resolved, baseCurrency, constraints);

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
          constraints,
          balanceByBucketKey,
          asOfByBucketKey);
    }
    return new GridData(turnoverByLeg, balanceByBucketKey, asOfByBucketKey);
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
      QueryConstraints constraints) {
    Map<Leg, List<RawTurnoverCell>> byLeg = new EnumMap<>(Leg.class);
    for (Measure measure : spec.measures()) {
      Leg leg =
          switch (measure.kind()) {
            case TURNOVER -> measure.leg();
            case COUNT_POSTINGS, COUNT_TRANSACTIONS -> Leg.NET;
            case CLOSING_BALANCE -> null;
          };
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
      QueryConstraints constraints) {
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      return queryRepository.accountTreeTurnover(
          types,
          resolved.start(),
          resolved.end(),
          baseCurrency,
          legName,
          includeClosed,
          includePending,
          constraints);
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
        constraints);
  }

  private void fetchClosingBalance(
      Dimension nonDateDim,
      List<String> types,
      Scope scope,
      List<MonthBucket> dateAxisBuckets,
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
    for (MonthBucket bucket : dateAxisBuckets) {
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
      return queryRepository.accountTreeClosingBalance(
          types, asOf, includeClosed, includePending, constraints);
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
