package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * The register's applied filter (register §2.3): the active account picker and the explicit account
 * selection within it, the date range, and an optional payee. Order is fixed date-ascending at 7a
 * (re-sorting is deferred — plan §14), so it is not part of the filter.
 *
 * @param accountIds the explicitly-ticked accounts within {@link #picker}; <em>empty means "every
 *     member of the picker"</em> — the service re-resolves it, and the entry dock deliberately does
 *     not serialise it, so widening the date range while all-ticked admits newly-qualifying
 *     accounts (issue transaction-register-ui/22)
 * @param picker the active tab-strip picker; decides what an empty {@link #accountIds} resolves to
 *     and is carried through the dock so a commit re-renders the same tab
 * @param fromDate inclusive lower bound; null for no lower bound
 * @param toDate inclusive upper bound; null for no upper bound
 * @param payeeId show only this payee's rows; null for all payees
 */
public record RegisterFilter(
    List<Long> accountIds,
    RegisterPicker picker,
    LocalDate fromDate,
    LocalDate toDate,
    Long payeeId) {

  /**
   * Defensively copy the account ids (null → empty) and default the picker (null → the default).
   */
  public RegisterFilter {
    accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    picker = picker == null ? RegisterPicker.DEFAULT : picker;
  }
}
