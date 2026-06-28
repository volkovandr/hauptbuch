package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.List;

/**
 * A transaction as submitted to {@link LedgerService} — the booking date, optional payee and note,
 * lifecycle, and the legs ({@link PostingDraft}s). Carries no amount of its own (data-model §3.5):
 * the amount lives in the legs.
 *
 * @param date booking date
 * @param payeeId external counterparty; {@code null} for transfers
 * @param note free-text note; nullable
 * @param lifecycle one of {@code pending_review | confirmed}
 * @param postings the legs; must balance (data-model §8 invariant 1)
 */
public record TransactionDraft(
    LocalDate date, Long payeeId, String note, String lifecycle, List<PostingDraft> postings) {

  /**
   * Defensively copy the legs to an immutable list, so the draft cannot be mutated after the fact.
   */
  public TransactionDraft {
    postings = postings == null ? List.of() : List.copyOf(postings);
  }

  /** A confirmed transaction with the given date, payee, note and legs. */
  public static TransactionDraft confirmed(
      LocalDate date, Long payeeId, String note, List<PostingDraft> postings) {
    return new TransactionDraft(date, payeeId, note, "confirmed", postings);
  }
}
