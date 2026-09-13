package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import volkovandr.hauptbuch.analytics.ReportFilter;
import volkovandr.hauptbuch.analytics.Scope;

/**
 * A Report's scope-subtree restriction and filter clauses (reporting.md §6.1, §6.2–§6.3) — the two
 * things every {@link ReportQueryRepository} turnover/closing-balance query ANDs onto its own WHERE
 * clause, on top of the fixed {@code types}/date-range/closed-account/pending-review predicate
 * every method already takes as separate parameters.
 *
 * @param accountSubtreeRoots restrict to these account subtrees (and their live descendants); empty
 *     means no restriction ({@link Scope#accountSubtreeRoots()})
 * @param filters the Report's AND-combined filter clauses ({@code ReportSpec#filters()})
 */
public record QueryConstraints(List<Long> accountSubtreeRoots, List<ReportFilter> filters) {

  /** No subtree restriction and no filters. */
  public static final QueryConstraints NONE = new QueryConstraints(List.of(), List.of());

  /** Defensively copies both lists. */
  public QueryConstraints {
    accountSubtreeRoots = List.copyOf(accountSubtreeRoots);
    filters = List.copyOf(filters);
  }
}
