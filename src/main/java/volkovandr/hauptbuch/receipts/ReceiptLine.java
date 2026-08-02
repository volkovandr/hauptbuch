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
 * @param aiTargetText the AI's raw target term (an unresolved category echo, or a transfer signal
 *     as {@code transfer: cash} / {@code transfer: card •1234}); null when the AI named no target.
 *     Rendered as a grey hint on an unresolved line and a provenance tooltip on a resolved one (9f)
 */
public record ReceiptLine(
    Long receiptLineId,
    long receiptId,
    String description,
    BigDecimal amount,
    Long accountId,
    Long personId,
    String note,
    Integer sortOrder,
    String aiTargetText) {}
