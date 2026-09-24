package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import volkovandr.hauptbuch.analytics.ReportFilter;

/**
 * A Report's filter clauses (reporting.md §6.2–§6.3) — what every {@link ReportQueryRepository}
 * turnover/closing-balance query ANDs onto its own WHERE clause, on top of the fixed {@code
 * types}/date-range/closed-account/pending-review predicate every method already takes as separate
 * parameters — plus the hierarchy nodes promoted to the axis's top level (reporting issue 08).
 *
 * @param filters the Report's AND-combined filter clauses ({@code ReportSpec#filters()})
 * @param promotedAccountIds the ticked Category/Account nodes of a filter on the axis dimension's
 *     own field: each is its own top-level group in the account-tree queries, and a real root's
 *     group no longer reaches into it
 * @param promotedTagIds {@code promotedAccountIds}' own mirror for the tag tree
 */
public record QueryConstraints(
    List<ReportFilter> filters, List<Long> promotedAccountIds, List<Long> promotedTagIds) {

  /** No filters. */
  public static final QueryConstraints NONE = new QueryConstraints(List.of());

  /** Defensively copies the lists. */
  public QueryConstraints {
    filters = List.copyOf(filters);
    promotedAccountIds = List.copyOf(promotedAccountIds);
    promotedTagIds = List.copyOf(promotedTagIds);
  }

  /** {@code filters} with nothing promoted: every node groups under its real root. */
  public QueryConstraints(List<ReportFilter> filters) {
    this(filters, List.of(), List.of());
  }
}
