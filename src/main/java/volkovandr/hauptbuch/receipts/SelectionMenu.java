package volkovandr.hauptbuch.receipts;

/**
 * Which actions the right-click context menu offers for a register selection (§5.2), and the counts
 * that drive the "N of M were committed — skipped" note. Members in an invalid state for an action
 * are skipped, never blocked.
 *
 * <p>On PC, delete <em>always</em> routes through the 3-way keep/delete-files dialog — {@code new}
 * included (2026-07-31) — so there is a single delete affordance, not the instant/dialog split of
 * 9b. The {@code discarded} state was retired the same day, so there is no Discard action either.
 *
 * @param total how many live receipts are selected
 * @param deletable how many are deletable through the ladder (every non-committed state)
 */
public record SelectionMenu(int total, int deletable) {

  /** Whether the selection has anything the delete dialog can act on. */
  public boolean canDelete() {
    return deletable > 0;
  }

  /** How many selected members are committed — skipped by delete (the 9g dialog's job). */
  public int skipped() {
    return total - deletable;
  }

  /**
   * The deletable count as a labelled, correctly-pluralised phrase, e.g. "1 receipt" / "3
   * receipts".
   */
  public String deletableLabel() {
    return deletable + (deletable == 1 ? " receipt" : " receipts");
  }
}
