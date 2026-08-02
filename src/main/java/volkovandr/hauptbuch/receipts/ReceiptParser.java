package volkovandr.hauptbuch.receipts;

/**
 * The receipt-parsing seam (ARCH-03, stage 9e): one Messages-API call that hands the model a
 * receipt image plus parsing instructions and returns its raw TOON body and usage. The production
 * implementation ({@code AnthropicReceiptParser}) wraps the official Anthropic Java SDK; tests
 * drive a fake so the suites never touch the network.
 *
 * <p>ARCH-08: the request carries only the document and the operator-curated parsing instructions
 * (the AI Vocabulary and the receipt's AI note) — never transactions, balances, or other ledger
 * contents.
 */
@FunctionalInterface
public interface ReceiptParser {

  /**
   * Parse one receipt image. Returns the model's raw body and token usage on any completed call
   * (even one whose body turns out to be undecodable — that is the caller's problem to detect);
   * throws {@link ReceiptParseException} only when the call could not complete (transport error,
   * authentication failure, an overloaded API).
   *
   * @param request the model, key, assembled prompt, and image media type
   * @param imageBytes the baked edited image the model reads
   * @return the raw TOON body and usage counts
   * @throws ReceiptParseException on a transport- or API-level failure
   */
  ReceiptParseResult parse(ReceiptParseRequest request, byte[] imageBytes);
}
