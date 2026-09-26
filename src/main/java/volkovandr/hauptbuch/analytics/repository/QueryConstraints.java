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
 * @param collectPostingIds whether each turnover group also returns the ids of the postings it sums
 *     ({@link RawTurnoverCell#postingIds()}) — the drill-down's posting set (reporting.md §12); off
 *     for an ordinary render, which never reads them
 */
public record QueryConstraints(
    List<ReportFilter> filters,
    List<Long> promotedAccountIds,
    List<Long> promotedTagIds,
    boolean collectPostingIds) {

  /** No filters. */
  public static final QueryConstraints NONE = new QueryConstraints(List.of());

  /** Defensively copies the lists. */
  public QueryConstraints {
    filters = List.copyOf(filters);
    promotedAccountIds = List.copyOf(promotedAccountIds);
    promotedTagIds = List.copyOf(promotedTagIds);
  }

  /** Posting ids not collected — every caller but the drill-down. */
  public QueryConstraints(
      List<ReportFilter> filters, List<Long> promotedAccountIds, List<Long> promotedTagIds) {
    this(filters, promotedAccountIds, promotedTagIds, false);
  }

  /** {@code filters} with nothing promoted: every node groups under its real root. */
  public QueryConstraints(List<ReportFilter> filters) {
    this(filters, List.of(), List.of());
  }

  /** These constraints, with each turnover group's posting ids collected (reporting.md §12). */
  public QueryConstraints collectingPostingIds() {
    return new QueryConstraints(filters, promotedAccountIds, promotedTagIds, true);
  }
}
