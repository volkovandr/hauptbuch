package volkovandr.hauptbuch.receipts;

import java.util.List;
import volkovandr.hauptbuch.ledger.AiSettings;

/**
 * One Batches-API create call (9h): the settings every member shares, plus the members themselves.
 *
 * <p>The shared system prompt is the point of the whole exercise — it is the cacheable prefix, and
 * a batch is the case where a cache write reliably pays for itself, so batch members always mark it
 * (no button, unlike single mode). ARCH-08 holds unchanged: the request carries only the documents
 * and the operator-curated parsing instructions.
 *
 * @param model the Anthropic model id (from {@code settings.ai_model})
 * @param apiKey the resolved API key (DB first, {@code ANTHROPIC_API_KEY} env fallback)
 * @param pricing the rates the adapter costs its own cache pre-warm call at for logging
 *     (receipt-processing/31) — each member's own {@code parse_cost} is still computed
 *     independently, from that member's own usage, once its result comes back
 * @param systemPrompt the instructions + vocabulary + TOON skeleton every member shares
 * @param mediaType the members' image MIME type ({@code image/jpeg} — the baked edited copies)
 * @param items the members, keyed by receipt id
 */
public record ReceiptBatchSubmission(
    String model,
    String apiKey,
    AiSettings pricing,
    String systemPrompt,
    String mediaType,
    List<ReceiptBatchItem> items) {

  /** Defensive copy so the submission a worker hands to the adapter cannot shift under it. */
  public ReceiptBatchSubmission {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
