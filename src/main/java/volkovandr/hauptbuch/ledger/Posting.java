package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;

/**
 * One signed leg of a transaction, hitting exactly one leaf account (data-model §3.6).
 *
 * <p>Sign convention: {@code + = debit, − = credit}. A transaction's legs sum to zero — in native
 * currency for a single-currency transaction, in base currency for a cross-currency one.
 *
 * <p>{@code amount} is in the account's native currency (the account carries the currency, so the
 * posting does not). {@code baseAmount} is {@code null} on the vast majority of postings — it is
 * set, frozen, only on the legs of a genuinely two-currency transaction, where it is the real
 * base-currency amount of that conversion event and must never be recomputed from the rate feed
 * (data-model §6.4).
 *
 * @param postingId surrogate PK; null for a not-yet-persisted posting
 * @param transactionId owning transaction
 * @param accountId the leaf account this leg hits
 * @param amount signed native amount
 * @param baseAmount frozen base-currency amount on cross-currency legs; null otherwise
 * @param reconciliation one of {@code unreconciled | cleared | reconciled}
 * @param note free-text note; nullable
 */
public record Posting(
    Long postingId,
    Long transactionId,
    Long accountId,
    BigDecimal amount,
    BigDecimal baseAmount,
    String reconciliation,
    String note) {}
