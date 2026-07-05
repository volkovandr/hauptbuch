package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the transaction register (register §2.4): a single live posting to an account the user
 * is viewing, carrying everything the register renders directly from that posting and its
 * transaction. The Category cell — a summary of the posting's <em>sibling</em> legs — is not here;
 * it is assembled in the service from a separate {@link RegisterCounterpartLeg} lookup, because
 * "biggest wins · +n / ⇄ Account" is a presentation rule, not SQL logic.
 *
 * <p>The load-bearing field is {@link #runningBalance}: the account's native balance up to and
 * including this posting, in {@code (date, transaction_id, posting_id)} order (register §2.7,
 * data-model §9). It is a windowed sum over the account's <em>whole</em> live history, so it stays
 * correct as-of even when older rows fall outside the viewed date range — and a backdated insert
 * shifts every row below it for free (data-model §8 invariant 5).
 *
 * @param postingId the leg this row is (the register's stable row identity)
 * @param transactionId the owning transaction
 * @param date booking date
 * @param accountId the viewed account this leg hits (the row's balance thread)
 * @param accountName the account's display name (the Account cell)
 * @param accountHue the account's stored register hue, for the same-hue zebra (register §2.8)
 * @param currencyCode the account's currency (the row's native currency)
 * @param baseCurrency whether this account's currency is the book's base (bare vs symbol display)
 * @param payeeName the transaction's payee display name; null for a payee-less transaction
 * @param amount the signed native amount of this leg
 * @param runningBalance the account's native running balance up to and including this leg
 * @param lifecycle {@code pending_review} rows render muted with no balance (register §2.10)
 * @param reconciliation drives the reconciliation status glyph
 */
public record RegisterRow(
    long postingId,
    long transactionId,
    LocalDate date,
    long accountId,
    String accountName,
    Integer accountHue,
    String currencyCode,
    boolean baseCurrency,
    String payeeName,
    BigDecimal amount,
    BigDecimal runningBalance,
    String lifecycle,
    String reconciliation) {}
