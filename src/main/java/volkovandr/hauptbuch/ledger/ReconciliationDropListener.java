package volkovandr.hauptbuch.ledger;

import java.util.Collection;

/**
 * Told by {@link LedgerService} when postings stop being {@code reconciled}, so a module that
 * attaches something to a reconciled posting can detach it (ADR 0003). Statement matches are the
 * one such attachment: a match exists only on a {@code reconciled} posting (statements.md §5).
 *
 * <p>The interface lives in {@code ledger} and the listener implements it, so {@code ledger} never
 * learns about the listening module — the same inversion {@code operations} uses for its {@code
 * ReferenceHolder}. Every listener Spring finds is called, in the editing or voiding transaction.
 */
@FunctionalInterface
public interface ReconciliationDropListener {

  /**
   * These postings were {@code reconciled} and no longer are: an edit changed the leg's amount, or
   * the transaction was voided. A leg an edit <em>deletes</em> is not reported — whatever hangs off
   * it goes with the row. Never called with an empty collection.
   */
  void reconciliationDropped(Collection<Long> postingIds);
}
