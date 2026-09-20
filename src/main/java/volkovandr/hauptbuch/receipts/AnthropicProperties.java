package volkovandr.hauptbuch.receipts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the Anthropic receipt-parse request, shared by the interactive ({@link
 * AnthropicReceiptParser}) and batch ({@link AnthropicReceiptBatchClient}) adapters. Deployment
 * config, not a secret and not model pricing — those live in the {@code settings} DB row ({@link
 * volkovandr.hauptbuch.ledger.AiSettings}, data-model §3.8) — so this stays a plain {@code
 * application.yaml} value.
 *
 * @param maxTokens the {@code max_tokens} budget for a receipt-parse request. On Sonnet-family
 *     models this cap covers adaptive thinking as well as the visible TOON output, so it must have
 *     headroom for both (issue 02: a 4096 cap truncated ordinary-sized receipts).
 * @param batchCacheWarmupEnabled whether {@link ReceiptBatchAnalyser#submit} submits a batch's
 *     first item alone, as its own real 1-item batch, before queuing the rest behind it
 *     (receipt-processing/31 v3) — a real batch's cache write is the only one a later batch's
 *     members reliably read; a standalone synchronous pre-warm call never worked (v1/v2 of this
 *     issue). On by default; an operator can turn it off if it ever proves not worth the extra
 *     round trip.
 */
@ConfigurationProperties("hauptbuch.receipts.ai")
public record AnthropicProperties(
    long maxTokens, @DefaultValue("true") boolean batchCacheWarmupEnabled) {}
