package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;

/**
 * Read/write access to the book's global settings, with the write-once base-currency guard
 * (data-model §3.8). This is the engine's gatekeeper for the "base currency is set" precondition:
 * {@link LedgerService} consults {@link #baseCurrency()} before recording any transaction.
 *
 * <p>The base-currency UI is stage 5; this service is born at stage 3 because the engine depends on
 * it. Write-once is enforced here (the application layer), not by a DB trigger — the same stance
 * the model takes on the sum-to-zero invariant (data-model T-DM-2).
 */
@Service
public class SettingsService {

  private final SettingsRepository settingsRepository;
  private final CurrencyRepository currencyRepository;

  /**
   * The {@code ANTHROPIC_API_KEY} env fallback (data-model §3.8): used when no key is stored in the
   * DB — the bootstrap/tests path. Empty string when unset (Spring maps the env var here).
   */
  private final String envApiKey;

  SettingsService(
      SettingsRepository settingsRepository,
      CurrencyRepository currencyRepository,
      @Value("${ANTHROPIC_API_KEY:}") String envApiKey) {
    this.settingsRepository = settingsRepository;
    this.currencyRepository = currencyRepository;
    this.envApiKey = envApiKey;
  }

  /** The full settings row (base currency + display name). */
  public Settings get() {
    return settingsRepository.load();
  }

  /**
   * The seeded currencies offered as base-currency choices on first run (plan stage 5). Only
   * meaningful while the base currency is unset; once locked, the settings screen shows the chosen
   * currency read-only and does not need the list.
   */
  public List<Currency> availableCurrencies() {
    return currencyRepository.findAll();
  }

  /** The base currency, or empty on a fresh book where it has not yet been set. */
  public Optional<String> baseCurrency() {
    return Optional.ofNullable(settingsRepository.load().baseCurrency());
  }

  /**
   * Set the base currency on first run. Write-once: refuses to overwrite a base currency that is
   * already set, because every frozen {@code baseAmount} and every booked conversion is denominated
   * in it (data-model §3.8).
   *
   * @throws IllegalStateException if the base currency is already set
   */
  @Transactional
  public void setBaseCurrency(String currencyCode) {
    if (settingsRepository.load().baseCurrency() != null) {
      throw new IllegalStateException(
          "Base currency is write-once and is already set; it cannot be changed.");
    }
    settingsRepository.updateBaseCurrency(currencyCode);
  }

  /** Set the freely-editable display name backing the greeting. */
  @Transactional
  public void setDisplayName(String displayName) {
    settingsRepository.updateDisplayName(displayName);
  }

  // ── AI parsing section (data-model §3.8, stage 9e) ──────────────────────────

  /**
   * The resolved AI parsing config the analyse worker calls the Messages API with: the stored model
   * (else the default {@code claude-sonnet-5}), the resolved API key (the DB value first, the
   * {@code ANTHROPIC_API_KEY} env as fallback), and the four price rates. The {@code apiKey} is
   * null only when neither the DB nor the env supplies one — the worker then fails the parse with a
   * clear reason rather than calling the API with no credential.
   */
  public AiSettings aiConfig() {
    AiSettings stored = settingsRepository.loadAi();
    String model =
        stored.model() == null || stored.model().isBlank()
            ? AiSettings.DEFAULT_MODEL
            : stored.model();
    String key = stored.apiKey() != null && !stored.apiKey().isBlank() ? stored.apiKey() : envKey();
    return new AiSettings(
        model,
        key,
        stored.priceIn(),
        stored.priceOut(),
        stored.priceCacheWrite(),
        stored.priceCacheRead());
  }

  /** The masked, render-safe AI section for the Settings screen — never the stored key itself. */
  public AiSettingsView aiSettingsView() {
    AiSettings stored = settingsRepository.loadAi();
    boolean keySet = stored.apiKey() != null && !stored.apiKey().isBlank();
    String last4 = keySet ? last4Of(stored.apiKey()) : null;
    return new AiSettingsView(
        stored.model(),
        keySet,
        last4,
        envKey() != null,
        stored.priceIn(),
        stored.priceOut(),
        stored.priceCacheWrite(),
        stored.priceCacheRead());
  }

  /** Set the Anthropic model id; a blank value clears it (falling back to the default). */
  @Transactional
  public void setAiModel(String model) {
    settingsRepository.updateAiModel(model == null || model.isBlank() ? null : model.strip());
  }

  /**
   * Set or clear the DB-stored API key (data-model §3.8, write-only). When {@code clear} is set the
   * stored key is removed (the parser falls back to the env key); otherwise a non-blank {@code
   * apiKey} replaces it and a blank one is left <em>unchanged</em> — a blank submit of a write-only
   * field must not silently wipe the key.
   */
  @Transactional
  public void setAiApiKey(String apiKey, boolean clear) {
    if (clear) {
      settingsRepository.updateAiApiKey(null);
    } else if (apiKey != null && !apiKey.isBlank()) {
      settingsRepository.updateAiApiKey(apiKey.strip());
    }
  }

  /**
   * The operator-edited receipt-parser system prompt, or null when none is stored — the receipts
   * module then falls back to its built-in default (owner feedback 2026-08-02, data-model §3.8).
   * Ledger stores the text opaquely; it never interprets it (that would couple ledger to the parse
   * format).
   */
  public String aiSystemPrompt() {
    String stored = settingsRepository.loadAiSystemPrompt();
    return stored == null || stored.isBlank() ? null : stored;
  }

  /** Set (or clear, when blank) the operator-edited receipt-parser system prompt. */
  @Transactional
  public void setAiSystemPrompt(String systemPrompt) {
    settingsRepository.updateAiSystemPrompt(
        systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt);
  }

  /** Set the four per-million-token USD price rates the frozen parse cost is computed from. */
  @Transactional
  public void setAiPrices(
      BigDecimal priceIn,
      BigDecimal priceOut,
      BigDecimal priceCacheWrite,
      BigDecimal priceCacheRead) {
    settingsRepository.updateAiPrices(priceIn, priceOut, priceCacheWrite, priceCacheRead);
  }

  /** The env fallback key, or null when the {@code ANTHROPIC_API_KEY} var is unset/blank. */
  private String envKey() {
    return envApiKey == null || envApiKey.isBlank() ? null : envApiKey;
  }

  private static String last4Of(String key) {
    return key.length() <= 4 ? key : key.substring(key.length() - 4);
  }
}
