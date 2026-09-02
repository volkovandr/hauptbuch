package volkovandr.hauptbuch.importer;

/**
 * The opening-balance reconciliation cells for one account-map row (import.md §5.1; plan c3),
 * pre-formatted for the review template. Money exports an account's opening balance as a
 * self-transfer and the target Hauptbuch account usually already has one — the owner picks the
 * winner or types an override; only the decision is recorded, the commit acts on it.
 *
 * @param moneyOpeningBalance Money's staged opening balance, {@code amount · dd.MM.yyyy}, or null
 *     when no staged file carries one for this account
 * @param hauptbuchOpeningBalance the mapped Hauptbuch account's own opening balance, same format,
 *     or null when it has none (or the row is unmapped / maps to a person leaf)
 * @param proposal the proposed winner — {@code keep_hauptbuch} / {@code take_money} — or null when
 *     there is nothing to reconcile ({@link OpeningBalanceReconciliation})
 * @param recordedChoice the outcome the owner has recorded ({@code keep_hauptbuch} / {@code
 *     take_money} / {@code override}), or null while undecided
 * @param recordedAmount the recorded {@code override} amount, German-formatted for the form's
 *     prefill, or null
 */
public record ImportOpeningBalanceCells(
    String moneyOpeningBalance,
    String hauptbuchOpeningBalance,
    String proposal,
    String recordedChoice,
    String recordedAmount) {

  /** Nothing on either side, nothing recorded — the default for a row. */
  public static final ImportOpeningBalanceCells EMPTY =
      new ImportOpeningBalanceCells(null, null, null, null, null);

  /**
   * Whether a reconciliation applies — Money staged an opening balance for this account, so the
   * owner needs to pick a winner (§5.1). Nothing to show otherwise.
   */
  public boolean reconciles() {
    return moneyOpeningBalance != null;
  }

  /** Whether both sides carry an opening balance — the genuine conflict (§5.1). */
  public boolean conflict() {
    return moneyOpeningBalance != null && hauptbuchOpeningBalance != null;
  }
}
