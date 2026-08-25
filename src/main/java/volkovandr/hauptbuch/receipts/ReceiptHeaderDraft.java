package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The post-process editor's header as Save persists it (plan §9f/§9g) — the operator's coerced
 * values, the write-side sibling of {@link ReceiptLineDraft}. Grouped into a record rather than
 * passed as seven positional arguments because {@code receipt}'s header columns are all nullable
 * and mostly the same type: {@code (null, payeeId, accountId, …)} reads as nothing at the call
 * site.
 *
 * @param receiptDate the booking date, or null while the draft is incomplete
 * @param payeeId the resolved (or freshly created) header payee, or null
 * @param accountId the paying account, or null
 * @param currencyCode the header currency, or null
 * @param totalAmount the editable grand total, or null when the operator left it blank
 * @param note the header note (9g), copied to {@code transaction.note} at Confirm; null when blank
 * @param receiptNumber the printed receipt/Beleg number (9g); null when blank
 * @param fundingTotal what comes off the paying account in its own currency (issue receipts/23);
 *     null for a single-currency receipt, and an operator-overtypeable estimate otherwise — which
 *     is exactly why it is persisted rather than re-proposed on every reopen (decision 2)
 * @param baseTotal the base-currency figure freezing the conversion (issue receipts/23); null
 *     unless the receipt is cross-currency and neither leg is the book's base currency
 */
public record ReceiptHeaderDraft(
    LocalDate receiptDate,
    Long payeeId,
    Long accountId,
    String currencyCode,
    BigDecimal totalAmount,
    String note,
    String receiptNumber,
    BigDecimal fundingTotal,
    BigDecimal baseTotal) {}
