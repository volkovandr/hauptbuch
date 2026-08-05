package volkovandr.hauptbuch.receipts;

/**
 * Everything the {@link ReceiptParser} needs for one Messages-API call except the image bytes,
 * which travel as a separate argument (a mutable array does not belong on a value record). The
 * prompt is assembled by the worker from the AI Vocabulary and the receipt's AI note (ARCH-08: only
 * the document + parsing instructions, never ledger contents).
 *
 * @param model the Anthropic model id (from {@code settings.ai_model}, default {@code
 *     claude-sonnet-5})
 * @param apiKey the resolved API key (DB first, {@code ANTHROPIC_API_KEY} env fallback)
 * @param systemPrompt the system instructions + vocabulary + TOON skeleton (the cacheable prefix)
 * @param userText the per-receipt AI note that accompanies the image, or empty
 * @param mediaType the image's MIME type ({@code image/jpeg} — the baked edited copy, 9c)
 * @param cachePrompt whether to mark the system prompt with a cache breakpoint (9h): the operator's
 *     "Analyse (cached)" choice in single mode, always set for a batch member. A cache write costs
 *     +25 % and only pays back on a second parse within the 5-minute TTL, so it is never implicit
 */
public record ReceiptParseRequest(
    String model,
    String apiKey,
    String systemPrompt,
    String userText,
    String mediaType,
    boolean cachePrompt) {}
