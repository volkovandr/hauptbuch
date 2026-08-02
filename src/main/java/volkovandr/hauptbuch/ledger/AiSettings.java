package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The settings AI section (data-model §3.8, stage 9e): the model the receipt parser calls, the
 * resolved API key, and the four per-million-token USD price rates a parse's frozen cost is
 * computed from. Kept off the {@link Settings} record — which backs the base-currency + greeting
 * screen — so the one DB-stored secret ({@code apiKey}) travels only through this purpose-built
 * type, read by the analyse worker and the Settings screen and nowhere else.
 *
 * <p>{@code apiKey} here is the <em>resolved</em> key (DB value, else the {@code ANTHROPIC_API_KEY}
 * env fallback) — never rendered back to the UI, which sees only a masked status. {@code model}
 * defaults to {@code claude-sonnet-5} when unset.
 *
 * @param model the Anthropic model id (never null once resolved; defaults to {@code
 *     claude-sonnet-5})
 * @param apiKey the resolved API key, or null when neither the DB nor the env supplies one
 * @param priceIn USD per million input tokens
 * @param priceOut USD per million output tokens
 * @param priceCacheWrite USD per million cache-write tokens
 * @param priceCacheRead USD per million cache-read tokens
 */
public record AiSettings(
    String model,
    String apiKey,
    BigDecimal priceIn,
    BigDecimal priceOut,
    BigDecimal priceCacheWrite,
    BigDecimal priceCacheRead) {

  /** The model id used when {@code settings.ai_model} is unset (data-model §3.8). */
  public static final String DEFAULT_MODEL = "claude-sonnet-5";

  /**
   * The USD cost of a parse from its recorded token counts and these rates (data-model §13.1) —
   * computed once at analyse time and frozen on the receipt, never recomputed on a later rate edit.
   * A null rate contributes nothing (an unconfigured price yields a zero component, not a failure).
   */
  public BigDecimal costOf(int tokensIn, int tokensOut, int tokensCacheWrite, int tokensCacheRead) {
    return component(priceIn, tokensIn)
        .add(component(priceOut, tokensOut))
        .add(component(priceCacheWrite, tokensCacheWrite))
        .add(component(priceCacheRead, tokensCacheRead))
        .setScale(6, RoundingMode.HALF_UP); // the parse_cost column's scale (numeric(12,6))
  }

  private static BigDecimal component(BigDecimal ratePerMillion, int tokens) {
    if (ratePerMillion == null || tokens == 0) {
      return BigDecimal.ZERO;
    }
    return ratePerMillion
        .multiply(BigDecimal.valueOf(tokens))
        .divide(BigDecimal.valueOf(1_000_000L));
  }
}
