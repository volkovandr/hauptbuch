package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The render model for the import review page (import.md §9). e′ delivered the per-account
 * statistics; c1 adds the account map. The category map and the issues list attach as further
 * panels in slices d and e.
 *
 * @param accounts one row per Money account that has a staged file, ordered by account name
 * @param accountMap the account-map panel (import.md §5.1; plan c1)
 */
public record ImportReview(List<AccountStatisticsRow> accounts, ImportAccountMap accountMap) {

  /** Defensive copy of the account rows. */
  public ImportReview {
    accounts = accounts == null ? List.of() : List.copyOf(accounts);
    accountMap = accountMap == null ? new ImportAccountMap(null, null, null, null) : accountMap;
  }

  /** True when nothing has been staged yet — the page shows only its explanatory copy. */
  public boolean empty() {
    return accounts.isEmpty();
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
