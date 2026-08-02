package volkovandr.hauptbuch.ledger.repository;

import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.Settings;

/**
 * Native-SQL access to the single-row {@code settings} entity (data-model §3.8). The row always
 * exists (seeded by V1 with a null base currency); this reads and updates it. The write-once guard
 * on {@code base_currency} is enforced in {@link SettingsService}, not here.
 */
@Repository
public class SettingsRepository {

  private static final String BASE_CURRENCY = "baseCurrency";
  private static final String DISPLAY_NAME = "displayName";

  private final JdbcClient jdbcClient;

  SettingsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Load the single settings row (base currency + display name). */
  public Settings load() {
    return jdbcClient
        .sql("select base_currency, display_name from settings where settings_id = 1")
        .query(Settings.class)
        .single();
  }

  /** Set the base currency (the write-once guard lives in {@code SettingsService}, not here). */
  public void updateBaseCurrency(String baseCurrency) {
    jdbcClient
        .sql("update settings set base_currency = :baseCurrency where settings_id = 1")
        .param(BASE_CURRENCY, baseCurrency)
        .update();
  }

  /** Set the freely-editable display name backing the greeting. */
  public void updateDisplayName(String displayName) {
    jdbcClient
        .sql("update settings set display_name = :displayName where settings_id = 1")
        .param(DISPLAY_NAME, displayName)
        .update();
  }

  /**
   * Load the AI section verbatim (data-model §3.8): the stored model (nullable), the stored API key
   * (nullable — the resolution to the env fallback and the default model happens in {@code
   * SettingsService}), and the four price rates. Columns are aliased to the {@link AiSettings}
   * component names.
   */
  public AiSettings loadAi() {
    return jdbcClient
        .sql(
            """
            select ai_model as model, ai_api_key as api_key,
                   ai_price_in as price_in, ai_price_out as price_out,
                   ai_price_cache_write as price_cache_write,
                   ai_price_cache_read as price_cache_read
            from settings where settings_id = 1
            """)
        .query(AiSettings.class)
        .single();
  }

  /** Set the Anthropic model id (null resets to the default at resolution time). */
  public void updateAiModel(String model) {
    jdbcClient
        .sql("update settings set ai_model = :model where settings_id = 1")
        .param("model", model)
        .update();
  }

  /**
   * Set (or clear, when null) the DB-stored API key — the one secret in the DB (data-model §3.8).
   */
  public void updateAiApiKey(String apiKey) {
    jdbcClient
        .sql("update settings set ai_api_key = :apiKey where settings_id = 1")
        .param("apiKey", apiKey)
        .update();
  }

  /**
   * The operator-edited receipt-parser system prompt, or null when none is stored (the {@code
   * ReceiptPromptBuilder} default is used at parse time). Data-model §3.8, owner feedback
   * 2026-08-02.
   */
  public String loadAiSystemPrompt() {
    return jdbcClient
        .sql("select ai_system_prompt from settings where settings_id = 1")
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** Set (or clear, when null) the operator-edited receipt-parser system prompt. */
  public void updateAiSystemPrompt(String systemPrompt) {
    jdbcClient
        .sql("update settings set ai_system_prompt = :prompt where settings_id = 1")
        .param("prompt", systemPrompt)
        .update();
  }

  /** Set the four per-million-token USD price rates a parse's frozen cost is computed from. */
  public void updateAiPrices(
      BigDecimal priceIn,
      BigDecimal priceOut,
      BigDecimal priceCacheWrite,
      BigDecimal priceCacheRead) {
    jdbcClient
        .sql(
            """
            update settings
            set ai_price_in = :priceIn, ai_price_out = :priceOut,
                ai_price_cache_write = :priceCacheWrite, ai_price_cache_read = :priceCacheRead
            where settings_id = 1
            """)
        .param("priceIn", priceIn)
        .param("priceOut", priceOut)
        .param("priceCacheWrite", priceCacheWrite)
        .param("priceCacheRead", priceCacheRead)
        .update();
  }
}
