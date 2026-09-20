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
 * @param batchCacheWarmupEnabled whether {@link AnthropicReceiptBatchClient#submit} sends its
 *     standalone cache pre-warm call before creating the batch (receipt-processing/31). On by
 *     default; an operator can turn it off if it ever proves not worth its own billed cost.
 * @param batchCacheWarmupDelaySeconds how long {@link AnthropicReceiptBatchClient#submit} pauses
 *     after the pre-warm call returns before creating the batch (receipt-processing/31 follow-up).
 *     The pre-warm call returning does not guarantee the cache write has landed everywhere the
 *     batch's members will be dispatched from — a short pause gives it time to propagate. Ignored
 *     when {@link #batchCacheWarmupEnabled} is off.
 */
@ConfigurationProperties("hauptbuch.receipts.ai")
public record AnthropicProperties(
    long maxTokens,
    @DefaultValue("true") boolean batchCacheWarmupEnabled,
    @DefaultValue("10") long batchCacheWarmupDelaySeconds) {}
