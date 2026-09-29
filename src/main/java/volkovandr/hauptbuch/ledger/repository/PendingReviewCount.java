package volkovandr.hauptbuch.ledger.repository;

import java.time.LocalDate;

/**
 * How many live {@code pending_review} transactions the book holds, as read by {@link
 * TransactionRepository#countPendingReview} for the main page's "pending to review" line (register
 * §2.3).
 *
 * @param pending how many live transactions are {@code pending_review}
 * @param overdue how many of those are dated before today
 * @param earliestDate the earliest pending row's date; null when there are none
 */
public record PendingReviewCount(long pending, long overdue, LocalDate earliestDate) {}
