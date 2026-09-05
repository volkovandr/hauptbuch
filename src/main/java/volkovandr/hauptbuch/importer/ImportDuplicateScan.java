package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The review's ledger-duplicate-scan panel and the fourth commit-gate condition (import.md §9; plan
 * f1) — the read model {@link ImportReviewService} folds into the review page, assembled by {@link
 * ImportDuplicateScanService}. e4's {@link ImportIssues} owns the other three gate conditions;
 * {@link ImportReview#commitReady()} combines both.
 *
 * <p>Q-IMP-5, settled at plan f1: the scan is a re-runnable snapshot with its own timestamp ({@code
 * ranAt}), re-run from a button — there is no ledger lock. The gate stays locked while the scan has
 * never run, while it is {@code stale} (a ledger transaction was created or edited, or a file was
 * staged, after {@code ranAt}), or while any match is still {@code pending}. Adjudicating a match
 * against a ledger transaction that later changes re-raises that decision to {@code pending} on the
 * next re-run (handled in {@link ImportDuplicateScanRepository}).
 *
 * @param everRun whether the scan has been run for this campaign at least once
 * @param stale whether ledger or staging activity postdates {@code ranAt} — a re-run is needed
 * @param ranAt when the scan last ran, {@code dd.MM.yyyy HH:mm}; null when it never has
 * @param pending the matches still awaiting the owner's decision
 * @param adjudicated the matches the owner has already decided ({@code import} or {@code skip})
 */
public record ImportDuplicateScan(
    boolean everRun,
    boolean stale,
    String ranAt,
    List<MatchRow> pending,
    List<MatchRow> adjudicated) {

  /** The scan panel before the first run. */
  public static final ImportDuplicateScan EMPTY =
      new ImportDuplicateScan(false, false, null, List.of(), List.of());

  /** Defensive copies of the lists. */
  public ImportDuplicateScan {
    pending = pending == null ? List.of() : List.copyOf(pending);
    adjudicated = adjudicated == null ? List.of() : List.copyOf(adjudicated);
  }

  /**
   * Whether this condition of the commit gate is satisfied — the scan has run, is current, and
   * every match it found has been adjudicated.
   */
  public boolean cleared() {
    return everRun && !stale && pending.isEmpty();
  }

  /** True when the scan ran and found no overlaps at all — the reassuring case. */
  public boolean ranClean() {
    return everRun && !stale && pending.isEmpty() && adjudicated.isEmpty();
  }

  /**
   * One match, pre-formatted for display. The staged and ledger sides share the date and amount by
   * construction of the match; both payees / notes are shown so the owner can tell the two events
   * apart.
   *
   * @param matchId the adjudication action's target
   * @param transactionId the live ledger transaction
   * @param date {@code dd.MM.yyyy}
   * @param moneyAccountName the Money account name the staged funding leg is for
   * @param amount the shared funding-leg amount, German-formatted to two places
   * @param stagedPayee the staged transaction's payee text — "—" when absent or destroyed
   * @param ledgerPayee the ledger transaction's payee — "—" when it has none (a transfer)
   * @param ledgerNote the ledger transaction's note — "—" when absent
   * @param decision {@code pending}, {@code import}, or {@code skip}
   */
  public record MatchRow(
      long matchId,
      long transactionId,
      String date,
      String moneyAccountName,
      String amount,
      String stagedPayee,
      String ledgerPayee,
      String ledgerNote,
      String decision) {}
}
