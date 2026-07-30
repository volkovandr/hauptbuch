package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A captured receipt scan moving through its stored lifecycle toward at most one transaction
 * (data-model §13.1). The original image is immutable (ARCH-07); the edited image and raw parse are
 * derived working copies added by later slices.
 *
 * <p>{@code state} and {@code deletedAt} are orthogonal: {@code state} tracks where the scan sits
 * in the workflow (see {@link ReceiptState}); {@code deletedAt} is the reversible soft-delete axis.
 * All the parsed header fields ({@code merchantText} … {@code accountId}) stay null until the AI
 * runs (9e); at 9b a receipt is only ever {@code new} (or {@code discarded}) with an image on disk.
 *
 * @param receiptId surrogate PK; null for a not-yet-persisted receipt
 * @param state one of {@link ReceiptState}'s values
 * @param capturedAt when the scan was taken/uploaded — the register and mobile grid's sort key
 * @param source how it arrived: {@code mobile}, {@code pc}, or {@code telegram}
 * @param originalPath root-relative path to the raw scan; never mutated (ARCH-07)
 * @param editedPath root-relative path to the derived, pre-processed image (9c); null until then
 * @param aiNote per-receipt prompt guidance (9c); null until entered
 * @param batchId Batches API id while processing (9h); null in single mode
 * @param parseRaw raw AI response retained for audit (9e); null until analysed
 * @param merchantText parsed merchant (9e); null until processed
 * @param receiptDate parsed receipt date (9e); null until processed
 * @param totalAmount parsed total (9e); null until processed
 * @param currencyCode parsed currency (9e); null until processed
 * @param accountId detected/picked paying account (9e); null until processed
 * @param transactionId the booked transaction (9g); null until committed
 * @param deletedAt reversible soft-delete timestamp; null while live
 */
public record Receipt(
    Long receiptId,
    String state,
    OffsetDateTime capturedAt,
    String source,
    String originalPath,
    String editedPath,
    String aiNote,
    String batchId,
    String parseRaw,
    String merchantText,
    LocalDate receiptDate,
    BigDecimal totalAmount,
    String currencyCode,
    Long accountId,
    Long transactionId,
    OffsetDateTime deletedAt) {

  /** The image path actually shown: the edited derivative when present, else the raw original. */
  public String displayPath() {
    return editedPath != null ? editedPath : originalPath;
  }
}
