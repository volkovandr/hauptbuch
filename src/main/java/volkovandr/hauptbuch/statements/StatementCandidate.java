package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A live ledger leg inside one statement line's date window (statements.md §4.1–4.3), before it is
 * sorted into a tier. The amount is the sum of the account's legs in the transaction — one leg by
 * construction (data-model §8, invariant 6).
 *
 * @param statementLineId the line it is a candidate for
 * @param postingId the leg
 * @param transactionId the transaction the leg belongs to
 * @param accountId the account the leg is on
 * @param accountName that account's name
 * @param amount the leg's amount in the account's currency
 * @param transactionDate the ledger's (purchase) date
 * @param payeeName the transaction's payee, or null
 * @param payeeSimilar whether the payee name is a case-insensitive substring of the line's text
 * @param reconciliation the leg's {@code unreconciled}, {@code cleared} or {@code reconciled} state
 * @param matchedElsewhere whether the leg is already matched to a line of another statement
 * @param dayDistance the absolute number of days between the transaction and the booking date
 */
public record StatementCandidate(
    long statementLineId,
    long postingId,
    long transactionId,
    long accountId,
    String accountName,
    BigDecimal amount,
    LocalDate transactionDate,
    String payeeName,
    boolean payeeSimilar,
    String reconciliation,
    boolean matchedElsewhere,
    int dayDistance) {}
