package volkovandr.hauptbuch.operations;

import java.util.List;

/**
 * The render model for the person-merge form (plan stage 8f, data-model §7): the source person
 * being removed, the currencies they carry a live position in (shown as directional sentences so
 * the merge reads as a deliberate, informed choice), and the live persons they can be folded into.
 *
 * <p>Merge is the only way to remove a person who is owed or owes money — it reassigns their
 * postings, per currency, onto the chosen target (the {@code operations} reassignment path), then
 * soft-deletes the now-zeroed source. It lives in {@code operations} because that reassignment is a
 * structural data-management op (CLAUDE.md §3), reaching the {@code debts} provisioning and the
 * {@code posting} table at once.
 *
 * @param personId the source person's id — the one being merged away
 * @param personName the source person's display name
 * @param positions the source's current per-currency positions as directional sentences (e.g.
 *     {@code "You owe Max 10,00 CHF"}); empty when the person is already settled, in which case a
 *     plain soft-delete would do and merge is merely the heavier alternative
 * @param targets the live persons the source can be merged into — everyone live except the source
 *     itself; empty when there is no one to merge into
 */
public record PersonMergeView(
    long personId, String personName, List<String> positions, List<TargetOption> targets) {

  /** Defensively copy the position and target lists to immutable lists. */
  public PersonMergeView {
    positions = List.copyOf(positions);
    targets = List.copyOf(targets);
  }

  /**
   * A candidate merge target: a live person the source can be folded into.
   *
   * @param personId the target person's id
   * @param name the target person's display name (disambiguates duplicate names in the picker)
   */
  public record TargetOption(long personId, String name) {}
}
