package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;

/**
 * A seeded draft line (data-model §13.2): the editable working copy between the raw parse and the
 * booked postings. Its {@code account_id} is either a category leaf or a real transfer target; a
 * set {@code person_id} makes it a beneficiary (person-debt) leg. Tags live in the {@code
 * receipt_line_tag} junction, not here.
 *
 * @param receiptLineId surrogate PK; null before persistence
 * @param receiptId the owning receipt
 * @param description the line description (item name, quantity folded in as {@code N× …})
 * @param amount the line amount in the paying account's native currency
 * @param accountId a category leaf or a real transfer target, or null (uncategorised)
 * @param personId set ⇒ a beneficiary leg into the person's debt leaf
 * @param note a free-text note, or null
 * @param sortOrder the line's position among the receipt's lines
 */
public record ReceiptLine(
    Long receiptLineId,
    long receiptId,
    String description,
    BigDecimal amount,
    Long accountId,
    Long personId,
    String note,
    Integer sortOrder) {}
