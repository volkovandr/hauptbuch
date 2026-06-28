package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An economic event (data-model §3.5). Has a booking {@code date} and an optional {@code payeeId} —
 * and <em>carries no amount</em>. The amount lives in the postings; a transaction total is a sum of
 * its legs. A single amount column would be a second source of truth that drifts on splits.
 *
 * <p>{@code lifecycle} and {@code deletedAt} are orthogonal axes, never one merged enum: {@code
 * lifecycle} is where the transaction sits in review ({@code pending_review → confirmed}); {@code
 * deletedAt} is whether it is live and when it was removed (reversible soft delete).
 *
 * @param transactionId surrogate PK; null for a not-yet-persisted transaction
 * @param date booking date
 * @param payeeId external counterparty; null for transfers
 * @param note free-text note; nullable
 * @param lifecycle one of {@code pending_review | confirmed}
 * @param createdAt audit timestamp
 * @param updatedAt audit timestamp
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Transaction(
    Long transactionId,
    LocalDate date,
    Long payeeId,
    String note,
    String lifecycle,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime deletedAt) {}
