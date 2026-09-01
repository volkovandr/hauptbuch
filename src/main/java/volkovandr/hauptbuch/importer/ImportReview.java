package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The render model for the import review page (import.md §9; plan e′). Born here as a
 * <strong>skeleton</strong> carrying only the per-account statistics — the account map, the
 * category map and the issues list attach as further panels in slices c, d and e.
 *
 * @param accounts one row per Money account that has a staged file, ordered by account name
 */
public record ImportReview(List<AccountStatisticsRow> accounts) {

  /** Defensive copy of the account rows. */
  public ImportReview {
    accounts = accounts == null ? List.of() : List.copyOf(accounts);
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
