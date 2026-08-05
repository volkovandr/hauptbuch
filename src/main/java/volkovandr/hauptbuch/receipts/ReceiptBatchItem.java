package volkovandr.hauptbuch.receipts;

/**
 * One member of a batch submission (9h): the receipt it belongs to, its per-receipt note, and its
 * image. The image travels already Base64-encoded — that is the shape the API wants, and it keeps
 * this a genuine value record rather than one wrapping a mutable {@code byte[]}.
 *
 * <p>{@code receiptId} doubles as the API's {@code custom_id}: results come back in any order, so
 * the id is what maps each one home.
 *
 * @param receiptId the receipt this member parses — also the {@code custom_id}
 * @param userText the user-turn text (the receipt's AI note, or the neutral default)
 * @param imageBase64 the baked edited image (9c), Base64-encoded, sent verbatim
 */
public record ReceiptBatchItem(long receiptId, String userText, String imageBase64) {}
