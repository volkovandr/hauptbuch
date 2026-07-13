package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;

/**
 * A leg of a transaction as submitted to {@link LedgerService} — before it is persisted and given a
 * {@code postingId}. The caller supplies the account, the signed native {@code amount}, and — only
 * on the legs of a genuinely cross-currency transaction — the frozen {@code baseAmount} (the real
 * base-currency value of that leg at the moment of the event, data-model §6.4).
 *
 * <p>For a single-currency transaction (the 95%+ case) {@code baseAmount} is left {@code null}: it
 * is derivable on the fly from the rate feed and is never stored.
 *
 * @param accountId the leaf account this leg hits
 * @param amount signed native amount ({@code + = debit, − = credit})
 * @param baseAmount frozen base-currency amount on a cross-currency leg; {@code null} otherwise
 * @param reconciliation one of {@code unreconciled | cleared | reconciled}
 * @param note free-text note; nullable
 */
public record PostingDraft(
    long accountId, BigDecimal amount, BigDecimal baseAmount, String reconciliation, String note) {

  /** The default reconciliation state a freshly-drafted leg carries. */
  private static final String UNRECONCILED = "unreconciled";

  /** A single-currency leg: native amount only, default reconciliation, no note. */
  public static PostingDraft of(long accountId, BigDecimal amount) {
    return new PostingDraft(accountId, amount, null, UNRECONCILED, null);
  }

  /** A single-currency leg carrying a posting-level note (a split line — register §3.7). */
  public static PostingDraft of(long accountId, BigDecimal amount, String note) {
    return new PostingDraft(accountId, amount, null, UNRECONCILED, note);
  }

  /** A cross-currency leg carrying its frozen base-currency amount. */
  public static PostingDraft ofCrossCurrency(
      long accountId, BigDecimal amount, BigDecimal baseAmount) {
    return new PostingDraft(accountId, amount, baseAmount, UNRECONCILED, null);
  }

  /**
   * A cross-currency leg carrying its frozen base amount and a posting-level note (a split line).
   */
  public static PostingDraft ofCrossCurrency(
      long accountId, BigDecimal amount, BigDecimal baseAmount, String note) {
    return new PostingDraft(accountId, amount, baseAmount, UNRECONCILED, note);
  }
}
