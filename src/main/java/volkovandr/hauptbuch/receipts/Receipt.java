package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * A captured receipt scan moving through its stored lifecycle toward at most one transaction
 * (data-model §13.1). The original image is immutable (ARCH-07); the edited image and raw parse are
 * derived working copies added by later slices.
 *
 * <p>{@code state} and {@code deletedAt} are orthogonal: {@code state} tracks where the scan sits
 * in the workflow (see {@link ReceiptState}); {@code deletedAt} is the reversible soft-delete axis.
 * All the parsed header fields ({@code merchantText} … {@code accountId}) stay null until the AI
 * runs (9e); a {@code new} receipt has only its original scan on disk, a {@code pre_processed} one
 * also its edited derivative (9c).
 *
 * @param receiptId surrogate PK; null for a not-yet-persisted receipt
 * @param state one of {@link ReceiptState}'s values
 * @param capturedAt when the scan was taken/uploaded — the register and mobile grid's sort key
 * @param source how it arrived: {@code mobile}, {@code pc}, or {@code telegram}
 * @param originalPath root-relative path to the raw scan; never mutated (ARCH-07)
 * @param editedPath root-relative path to the derived, pre-processed image (9c); null until then
 * @param editRecipe client-side edit parameters as JSON (crop/rotation/tilt/filters, 9c), saved
 *     with the bake and replayed onto the original on re-edit; null until the first Save
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
 * @param parseError why the parse failed (9e); null on success or before analysis
 * @param tokensIn input tokens billed for the parse (9e); null until analysed
 * @param tokensOut output tokens billed for the parse (9e); null until analysed
 * @param tokensCacheWrite cache-write tokens billed (9e); null until analysed
 * @param tokensCacheRead cache-read tokens billed (9e); null until analysed
 * @param parseCost the frozen USD parse cost (9e), computed from the settings rates at analyse time
 *     and never recomputed; null until analysed
 * @param merchantCity parsed merchant city (9e); null until processed
 * @param merchantCountry parsed merchant country (9e); null until processed
 * @param receiptTime parsed printed time, no zone (9e); null until processed
 * @param receiptNumber parsed printed receipt/Beleg number (9e); null until processed
 * @param payeeId the header payee the operator picks or creates at post-process Save (9f); null
 *     until then ({@code merchantText} stays the parse fact)
 */
public record Receipt(
    Long receiptId,
    String state,
    OffsetDateTime capturedAt,
    String source,
    String originalPath,
    String editedPath,
    String editRecipe,
    String aiNote,
    String batchId,
    String parseRaw,
    String merchantText,
    LocalDate receiptDate,
    BigDecimal totalAmount,
    String currencyCode,
    Long accountId,
    Long transactionId,
    OffsetDateTime deletedAt,
    String parseError,
    Integer tokensIn,
    Integer tokensOut,
    Integer tokensCacheWrite,
    Integer tokensCacheRead,
    BigDecimal parseCost,
    String merchantCity,
    String merchantCountry,
    LocalTime receiptTime,
    String receiptNumber,
    Long payeeId) {

  /** The image path actually shown: the edited derivative when present, else the raw original. */
  public String displayPath() {
    return editedPath != null ? editedPath : originalPath;
  }

  /**
   * The merchant rendered as {@code name - city - country}, the way the register shows a payee
   * (owner feedback 2026-08-02) — blank parts are dropped, so a receipt with only a name shows just
   * the name. Null when no merchant part was parsed.
   */
  public String merchantDisplay() {
    StringBuilder out = new StringBuilder();
    for (String part : new String[] {merchantText, merchantCity, merchantCountry}) {
      if (part != null && !part.isBlank()) {
        if (out.length() > 0) {
          out.append(" - ");
        }
        out.append(part.strip());
      }
    }
    return out.isEmpty() ? null : out.toString();
  }
}
