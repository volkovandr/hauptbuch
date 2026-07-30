package volkovandr.hauptbuch.receipts;

/**
 * Which actions the right-click context menu offers for a register selection (§5.2), and the counts
 * that drive the "N of M were not …" skip notes. Members in an invalid state for an action are
 * skipped, never blocked.
 *
 * @param total how many live receipts are selected
 * @param deletable how many are deletable through the ladder (every non-committed state)
 * @param discardable how many can be discarded (every non-committed state)
 * @param allDeletableNew whether every deletable member is {@code new} — the whole selection then
 *     deletes instantly with files removed; otherwise deletion routes through the keep/delete-files
 *     dialog
 */
public record SelectionMenu(int total, int deletable, int discardable, boolean allDeletableNew) {

  /**
   * Delete removes files with no dialog: there is something to delete and it is all {@code new}.
   */
  public boolean instantDelete() {
    return deletable > 0 && allDeletableNew;
  }

  /** Delete routes through the keep/delete-files dialog: deletable members that aren't all new. */
  public boolean fileChoiceDelete() {
    return deletable > 0 && !allDeletableNew;
  }

  /** How many selected members are committed — skipped by delete/discard (the 9g dialog's job). */
  public int skipped() {
    return total - deletable;
  }
}
