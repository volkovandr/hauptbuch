package volkovandr.hauptbuch.receipts;

/**
 * What one batch member came back as (9h). Exactly one of {@code result} / {@code failure} is set:
 * a succeeded member carries its raw body and token counts, an errored, expired, or canceled one
 * carries the reason. Whether a succeeded body actually <em>decodes</em> is not this type's problem
 * — that is the lenient seeding path's job, the same one single mode uses.
 *
 * @param receiptId the receipt this outcome belongs to (the API's {@code custom_id})
 * @param result the raw body and usage on success; null on failure
 * @param failure why the member did not produce a body; null on success
 */
public record ReceiptBatchOutcome(long receiptId, ReceiptParseResult result, String failure) {

  /** A succeeded member: the model returned a body (decodable or not). */
  public static ReceiptBatchOutcome succeeded(long receiptId, ReceiptParseResult result) {
    return new ReceiptBatchOutcome(receiptId, result, null);
  }

  /** A member the API could not complete — errored, expired, or canceled. */
  public static ReceiptBatchOutcome failed(long receiptId, String failure) {
    return new ReceiptBatchOutcome(receiptId, null, failure);
  }

  /** Whether the member returned a body. */
  public boolean isSucceeded() {
    return result != null;
  }
}
