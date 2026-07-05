package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * The register's applied filter (register §2.3): the viewed accounts, the date range, and an
 * optional payee. Order is fixed date-ascending at 7a (re-sorting is deferred — plan §14), so it is
 * not part of the filter.
 *
 * @param accountIds the viewed accounts; empty means "the default set" is resolved by the service
 * @param fromDate inclusive lower bound; null for no lower bound
 * @param toDate inclusive upper bound; null for no upper bound
 * @param payeeId show only this payee's rows; null for all payees
 */
public record RegisterFilter(
    List<Long> accountIds, LocalDate fromDate, LocalDate toDate, Long payeeId) {

  /** Defensively copy the account ids to an immutable list (null becomes empty). */
  public RegisterFilter {
    accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
  }
}
