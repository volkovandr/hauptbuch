package volkovandr.hauptbuch.operations;

import java.util.List;

/**
 * A module that stores references to people and accounts outside the postings, which the structural
 * operations here must keep pointing at something (data-model §14.3, recurring sub-plan slice f).
 * Recurring templates are the one such holder today.
 *
 * <p>The interface lives in {@code operations} and the holder implements it, so {@code operations}
 * never depends on the holder's module: that module already depends on {@code operations} (a
 * template books through the dock), and the reverse import would be a cycle {@code verify()}
 * forbids. Every operation calls every holder Spring finds, in the same transaction as its own
 * work.
 */
public interface ReferenceHolder {

  /** A person merge folded {@code fromPersonId} into {@code toPersonId}: follow it. */
  void reassignPerson(long fromPersonId, long toPersonId);

  /**
   * A subdivision moved the postings of {@code fromAccountId} onto {@code toAccountId}, its new
   * catch-all child: follow them.
   */
  void reassignAccount(long fromAccountId, long toAccountId);

  /**
   * What still uses any of {@code accountIds}, each named for the operator (e.g. {@code recurring
   * template 'Streaming'}), for a deletion of those accounts to refuse. Empty when nothing does.
   */
  List<String> usersOf(List<Long> accountIds);
}
