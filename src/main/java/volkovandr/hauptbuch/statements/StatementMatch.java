package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A confirmed match of one statement line to one posting (data-model §15), with the leg as the
 * statement page shows it. A match exists only on a {@code reconciled} posting (statements.md §5).
 *
 * @param statementLineId the matched line
 * @param postingId the matched leg
 * @param transactionId the leg's transaction
 * @param transactionDate the ledger's date
 * @param payeeName the transaction's payee, or null
 * @param amount the leg's amount
 * @param reconciliation the leg's reconciliation state
 */
public record StatementMatch(
    long statementLineId,
    long postingId,
    long transactionId,
    LocalDate transactionDate,
    String payeeName,
    BigDecimal amount,
    String reconciliation) {}
