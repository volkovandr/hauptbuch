package volkovandr.hauptbuch.importer;

import java.util.List;
import java.util.Map;

/**
 * The render model for the import review page (import.md §9). e′ delivered the per-account
 * statistics; c1 adds the account map; c3 adds the opening-balance reconciliation cells beside it;
 * d1 adds the category map; d2 adds the payee summary. The issues list attaches as a further panel
 * in slice e.
 *
 * @param accounts one row per Money account that has a staged file, ordered by account name
 * @param accountMap the account-map panel (import.md §5.1; plan c1)
 * @param openingBalances the opening-balance reconciliation cells, keyed by {@code import_account}
 *     id (import.md §5.1; plan c3)
 * @param categoryMap the category-map panel (import.md §5.2; plan d1)
 * @param payees the payee counts — payees are auto-created, never mapped, so the review only counts
 *     them (import.md §5.3; plan d2)
 */
public record ImportReview(
    List<AccountStatisticsRow> accounts,
    ImportAccountMap accountMap,
    Map<Long, ImportOpeningBalanceCells> openingBalances,
    ImportCategoryMap categoryMap,
    ImportPayeeSummary payees) {

  /** Defensive copies. */
  public ImportReview {
    accounts = accounts == null ? List.of() : List.copyOf(accounts);
    accountMap = accountMap == null ? new ImportAccountMap(null, null, null, null) : accountMap;
    openingBalances = openingBalances == null ? Map.of() : Map.copyOf(openingBalances);
    categoryMap = categoryMap == null ? new ImportCategoryMap(null, null, null) : categoryMap;
    payees = payees == null ? ImportPayeeSummary.EMPTY : payees;
  }

  /** True when nothing has been staged yet — the page shows only its explanatory copy. */
  public boolean empty() {
    return accounts.isEmpty();
  }

  /**
   * The opening-balance cells for one account-map row, never null — {@link
   * ImportOpeningBalanceCells#EMPTY} when the row has nothing to reconcile (plan c3).
   */
  public ImportOpeningBalanceCells openingBalanceFor(long importAccountId) {
    return openingBalances.getOrDefault(importAccountId, ImportOpeningBalanceCells.EMPTY);
  }

  /**
   * One account's verification figures, pre-formatted for display (import.md §9.4). {@code netSum}
   * is German-formatted and bare — QIF carries no currency, so the number is ticked against Money's
   * own balance as-is.
   *
   * @param moneyAccountName the Money account name
   * @param transactionCount how many transactions were staged for it
   * @param netSum the funding-leg net, German-formatted to two places
   * @param dateRange the staged date span, {@code dd.MM.yyyy – dd.MM.yyyy} (one date if they match)
   */
  public record AccountStatisticsRow(
      String moneyAccountName, long transactionCount, String netSum, String dateRange) {}
}
