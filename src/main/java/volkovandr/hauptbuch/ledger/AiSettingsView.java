package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;

/**
 * The masked, render-safe view of the settings AI section for the Settings screen (data-model
 * §3.8). The API key is <em>write-only</em>: the screen never renders the stored value, only
 * whether one is set and its last four characters, plus whether the {@code ANTHROPIC_API_KEY} env
 * fallback is present — enough to tell the operator what the parser will actually use, nothing they
 * could read the secret from.
 *
 * @param model the stored model id, or null (the screen shows the default as a placeholder)
 * @param keySet whether a key is stored in the DB
 * @param keyLast4 the last four characters of the stored key, or null when none is stored
 * @param envKeyPresent whether the {@code ANTHROPIC_API_KEY} env fallback is set
 * @param priceIn USD per million input tokens (nullable)
 * @param priceOut USD per million output tokens (nullable)
 * @param priceCacheWrite USD per million cache-write tokens (nullable)
 * @param priceCacheRead USD per million cache-read tokens (nullable)
 */
public record AiSettingsView(
    String model,
    boolean keySet,
    String keyLast4,
    boolean envKeyPresent,
    BigDecimal priceIn,
    BigDecimal priceOut,
    BigDecimal priceCacheWrite,
    BigDecimal priceCacheRead) {

  /** The default model id shown as a placeholder when none is stored (data-model §3.8). */
  public String defaultModel() {
    return AiSettings.DEFAULT_MODEL;
  }
}
