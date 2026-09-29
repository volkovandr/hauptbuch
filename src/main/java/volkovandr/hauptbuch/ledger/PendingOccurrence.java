package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;

/**
 * A live {@code pending_review} transaction a recurring template booked (data-model §14.3): what a
 * template edit or delete decides to keep or remove.
 *
 * @param transactionId the booked transaction
 * @param date its date — the occurrence date, since the dock would have confirmed it on any edit
 */
public record PendingOccurrence(long transactionId, LocalDate date) {}
