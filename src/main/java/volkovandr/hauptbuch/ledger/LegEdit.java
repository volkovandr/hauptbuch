package volkovandr.hauptbuch.ledger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * What editing a transaction does to its legs (ADR 0003), worked out before anything is written:
 * which existing legs are updated in place, which new legs are inserted, which old legs are
 * deleted, and which {@code reconciled} legs lose that state — on an amount change, and on a change
 * of the transaction's date (a match is made on the date, data-model §3.6). A pure function of the
 * old and new legs, so {@link LedgerService} only has to carry it out.
 *
 * @param updates the existing legs to update in place, with the tags they should carry afterwards
 * @param inserts the new legs that have no partner
 * @param deletedPostingIds the existing legs that have no partner
 * @param droppedPostingIds the {@code reconciled} legs an amount or date change drops to {@code
 *     unreconciled}, in the order of the new legs
 */
record LegEdit(
    List<Update> updates,
    List<PostingDraft> inserts,
    List<Long> deletedPostingIds,
    List<Long> droppedPostingIds) {

  private static final String RECONCILED = "reconciled";
  private static final String UNRECONCILED = "unreconciled";

  /** One existing leg updated in place and the tags it carries after the edit. */
  record Update(Posting leg, List<Long> tagIds) {}

  /**
   * Pair each new leg on a real own account with the existing leg on the same account. Any other
   * new leg — and any old leg left over — has no partner, which for income, expense and person
   * leaves (accounts that may repeat) is the normal case.
   *
   * @param realOwnAccount whether an account id is a real own account, the only kind legs are
   *     paired on (data-model §8 invariant 6)
   * @param dateChanged whether the edit moves the transaction to another date
   */
  static LegEdit plan(
      long transactionId,
      List<Posting> oldLegs,
      List<PostingDraft> newLegs,
      LongPredicate realOwnAccount,
      boolean dateChanged) {
    Map<Long, Posting> pairable = new LinkedHashMap<>();
    for (Posting old : oldLegs) {
      pairable.putIfAbsent(old.accountId(), old);
    }

    Set<Long> pairedIds = new HashSet<>();
    List<Update> updates = new ArrayList<>();
    List<PostingDraft> inserts = new ArrayList<>();
    List<Long> dropped = new ArrayList<>();
    for (PostingDraft leg : newLegs) {
      Posting partner =
          realOwnAccount.test(leg.accountId()) ? pairable.remove(leg.accountId()) : null;
      if (partner == null) {
        inserts.add(leg);
        continue;
      }
      pairedIds.add(partner.postingId());
      Update update = pair(transactionId, partner, leg, dateChanged);
      updates.add(update);
      if (RECONCILED.equals(partner.reconciliation())
          && !RECONCILED.equals(update.leg().reconciliation())) {
        dropped.add(partner.postingId());
      }
    }

    List<Long> deleted =
        oldLegs.stream().map(Posting::postingId).filter(id -> !pairedIds.contains(id)).toList();
    return new LegEdit(updates, inserts, deleted, dropped);
  }

  /**
   * A paired leg keeps its reconciliation while its amount is unchanged, else starts over; a new
   * transaction date also unreconciles a {@code reconciled} leg.
   */
  private static Update pair(
      long transactionId, Posting old, PostingDraft leg, boolean dateChanged) {
    boolean keep =
        old.amount().compareTo(leg.amount()) == 0
            && !(dateChanged && RECONCILED.equals(old.reconciliation()));
    String reconciliation = keep ? old.reconciliation() : UNRECONCILED;
    return new Update(
        new Posting(
            old.postingId(),
            transactionId,
            old.accountId(),
            leg.amount(),
            leg.baseAmount(),
            reconciliation,
            leg.note()),
        leg.tagIds());
  }
}
