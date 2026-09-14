package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import volkovandr.hauptbuch.analytics.ReportFilter;

/**
 * A Report's filter clauses (reporting.md §6.2–§6.3) — what every {@link ReportQueryRepository}
 * turnover/closing-balance query ANDs onto its own WHERE clause, on top of the fixed {@code
 * types}/date-range/closed-account/pending-review predicate every method already takes as separate
 * parameters.
 *
 * @param filters the Report's AND-combined filter clauses ({@code ReportSpec#filters()})
 */
public record QueryConstraints(List<ReportFilter> filters) {

  /** No filters. */
  public static final QueryConstraints NONE = new QueryConstraints(List.of());

  /** Defensively copies the list. */
  public QueryConstraints {
    filters = List.copyOf(filters);
  }
}
