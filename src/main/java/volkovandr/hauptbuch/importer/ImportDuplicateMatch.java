package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the ledger duplicate scan's result, joined for display (import.md §9; plan f1) —
 * {@link volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository#findMatchRows}'s
 * row shape. The staged and ledger sides share date and funding amount by construction of the match
 * (that is what made them a match); both are shown so the owner can tell the two events apart by
 * payee / note before deciding.
 *
 * @param importDuplicateMatchId the adjudication action's target
 * @param importTransactionId the staged transaction
 * @param transactionId the live ledger transaction
 * @param date the shared booking date, {@code dd.MM.yyyy} rendering left to the panel
 * @param moneyAccountName the Money account name the staged funding leg is for
 * @param amount the shared funding-leg amount (signed, native)
 * @param stagedPayee the staged transaction's payee text (may be null / destroyed)
 * @param ledgerPayee the ledger transaction's payee name (may be null — transfers have none)
 * @param ledgerNote the ledger transaction's note (may be null)
 * @param adjudication {@code pending}, {@code import}, or {@code skip}
 */
public record ImportDuplicateMatch(
    long importDuplicateMatchId,
    long importTransactionId,
    long transactionId,
    LocalDate date,
    String moneyAccountName,
    BigDecimal amount,
    String stagedPayee,
    String ledgerPayee,
    String ledgerNote,
    String adjudication) {}
