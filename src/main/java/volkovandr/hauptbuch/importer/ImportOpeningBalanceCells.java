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
 * @param recordedSummary a compact one-line rendering of the recorded outcome for the collapsed
 *     row's summary (e.g. {@code opening balance 1.234,56 at 01.01.2004 (Money)}), or null while
 *     undecided, when nothing reconciles, or when the winning amount is zero and the choice was not
 *     an override (import.md §5.1; the owner asked to omit a boring zero note but always keep a
 *     deliberate override)
 * @param autoResolves whether the reconciliation carries no real decision — Money staged a zero
 *     opening balance and the mapped Hauptbuch account has none, so {@code take_money} applies with
 *     nothing to weigh (§5.1). The collapsed row does not flag it; the commit falls back to {@link
 *     #proposal} when no choice was recorded. The expanded form still renders so the owner may
 *     override if they want to.
 */
public record ImportOpeningBalanceCells(
    String moneyOpeningBalance,
    String hauptbuchOpeningBalance,
    String proposal,
    String recordedChoice,
    String recordedAmount,
    String recordedSummary,
    boolean autoResolves) {

  /** Nothing on either side, nothing recorded — the default for a row. */
  public static final ImportOpeningBalanceCells EMPTY =
      new ImportOpeningBalanceCells(null, null, null, null, null, null, false);

  /**
   * Whether a reconciliation applies — Money staged an opening balance for this account, so the
   * owner needs to pick a winner (§5.1). Nothing to show otherwise.
   */
  public boolean reconciles() {
    return moneyOpeningBalance != null;
  }

  /**
   * Whether the owner still has to pick a winner — a reconciliation applies, no choice is recorded
   * yet, and it is not a zero-into-nothing that resolves itself ({@code autoResolves}). The
   * collapsed row flags this so it stays visible without re-expanding (§5.1).
   */
  public boolean needsResolution() {
    return reconciles() && recordedChoice == null && !autoResolves;
  }

  /** Whether both sides carry an opening balance — the genuine conflict (§5.1). */
  public boolean conflict() {
    return moneyOpeningBalance != null && hauptbuchOpeningBalance != null;
  }
}
