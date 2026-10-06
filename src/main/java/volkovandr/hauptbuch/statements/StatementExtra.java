package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A leg on the statement's account, dated in its period, that the statement does not account for
 * (statements.md §6.3).
 *
 * @param postingId the leg
 * @param transactionId the leg's transaction
 * @param transactionDate the ledger's date
 * @param payeeName the transaction's payee, or null
 * @param note the transaction's note, or null
 * @param amount the leg's amount
 */
public record StatementExtra(
    long postingId,
    long transactionId,
    LocalDate transactionDate,
    String payeeName,
    String note,
    BigDecimal amount) {}
