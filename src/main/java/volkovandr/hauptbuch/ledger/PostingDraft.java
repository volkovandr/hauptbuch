package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * A leg of a transaction as submitted to {@link LedgerService} — before it is persisted and given a
 * {@code postingId}. The caller supplies the account, the signed native {@code amount}, and — only
 * on the legs of a genuinely cross-currency transaction — the frozen {@code baseAmount} (the real
 * base-currency value of that leg at the moment of the event, data-model §6.4).
 *
 * <p>For a single-currency transaction (the 95%+ case) {@code baseAmount} is left {@code null}: it
 * is derivable on the fly from the rate feed and is never stored.
 *
 * <p>{@code tagIds} are the tags this leg carries (data-model §10.2 — tags attach per-posting, not
 * per-transaction). The caller ({@code operations}) has already resolved the dock's chips to ids
 * via {@code categories}; the engine treats them as opaque, persisting one {@code posting_tag} row
 * per id. Empty on the overwhelming majority of legs; the factories default it so untagged callers
 * are unaffected and a tagged caller adds {@link #withTags}.
 *
 * @param accountId the leaf account this leg hits
 * @param amount signed native amount ({@code + = debit, − = credit})
 * @param baseAmount frozen base-currency amount on a cross-currency leg; {@code null} otherwise
 * @param reconciliation one of {@code unreconciled | cleared | reconciled}
 * @param note free-text note; nullable
 * @param tagIds the tag ids this leg carries (data-model §10.2); never null, defaults empty
 */
public record PostingDraft(
    long accountId,
    BigDecimal amount,
    BigDecimal baseAmount,
    String reconciliation,
    String note,
    List<Long> tagIds) {

  /** The default reconciliation state a freshly-drafted leg carries. */
  private static final String UNRECONCILED = "unreconciled";

  /** Defensively copy the tag ids to an immutable list (null-safe), so the draft cannot mutate. */
  public PostingDraft {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }

  /** A single-currency leg: native amount only, default reconciliation, no note, no tags. */
  public static PostingDraft of(long accountId, BigDecimal amount) {
    return new PostingDraft(accountId, amount, null, UNRECONCILED, null, List.of());
  }

  /** A single-currency leg carrying a posting-level note (a split line — register §3.7). */
  public static PostingDraft of(long accountId, BigDecimal amount, String note) {
    return new PostingDraft(accountId, amount, null, UNRECONCILED, note, List.of());
  }

  /** A cross-currency leg carrying its frozen base-currency amount. */
  public static PostingDraft ofCrossCurrency(
      long accountId, BigDecimal amount, BigDecimal baseAmount) {
    return new PostingDraft(accountId, amount, baseAmount, UNRECONCILED, null, List.of());
  }

  /**
   * A cross-currency leg carrying its frozen base amount and a posting-level note (a split line).
   */
  public static PostingDraft ofCrossCurrency(
      long accountId, BigDecimal amount, BigDecimal baseAmount, String note) {
    return new PostingDraft(accountId, amount, baseAmount, UNRECONCILED, note, List.of());
  }

  /**
   * This leg with the given tags attached (register §3.6) — the {@code operations} commit path
   * resolves the dock's chips to ids and threads them onto each leg before recording. Returns a new
   * draft; the tag ids are defensively copied by the canonical constructor.
   */
  public PostingDraft withTags(List<Long> tagIds) {
    return new PostingDraft(accountId, amount, baseAmount, reconciliation, note, tagIds);
  }
}
