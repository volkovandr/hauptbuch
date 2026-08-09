package volkovandr.hauptbuch.receipts;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties("hauptbuch.receipts.ai")
public record AnthropicProperties(long maxTokens) {}
