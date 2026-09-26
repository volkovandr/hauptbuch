package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * What stays the same across every {@link ReportDataFetcher#fetchGridData} call of one render: the
 * whole-range fetch and each expanded Date row's day fetch (reporting.md §9.1) differ only in their
 * range, buckets and granularity, which {@code fetchGridData} takes on its own.
 *
 * @param spec the Report being rendered
 * @param axes its resolved axis shape
 * @param types the Scope's account types
 * @param today the date a closing balance's as-of date is clamped to (§8.2)
 * @param baseCurrency the settings row's base currency
 * @param expandedKeys the expanded nodes of the non-Date dimension, at any depth (§9.1, §9.2)
 * @param collectPostingIds whether every turnover group also carries the ids of the postings it
 *     sums — on only for a drill-down (§12), which reads them
 */
record FetchContext(
    ReportSpec spec,
    AxisPlan axes,
    List<String> types,
    LocalDate today,
    String baseCurrency,
    Set<String> expandedKeys,
    boolean collectPostingIds) {

  /** An ordinary render's context: posting ids not collected. */
  FetchContext(
      ReportSpec spec,
      AxisPlan axes,
      List<String> types,
      LocalDate today,
      String baseCurrency,
      Set<String> expandedKeys) {
    this(spec, axes, types, today, baseCurrency, expandedKeys, false);
  }

  boolean includeClosedAccounts() {
    return spec.scope().includeClosedAccounts();
  }

  boolean includePendingReview() {
    return spec.scope().includePendingReview();
  }
}
