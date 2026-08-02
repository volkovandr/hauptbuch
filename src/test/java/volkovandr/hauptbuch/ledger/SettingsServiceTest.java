package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;

/**
 * Unit tier (plan §1.5): the write-once base-currency guard (data-model §3.8). The engine relies on
 * this guard to keep every frozen {@code baseAmount} interpretable, so it is enforced here at the
 * application layer rather than left to a DB trigger.
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

  private static final String CHF = "CHF";
  private static final String EUR = "EUR";

  @Mock private SettingsRepository settingsRepository;
  @Mock private CurrencyRepository currencyRepository;

  @Test
  void setsBaseCurrencyOnFreshBook() {
    when(settingsRepository.load()).thenReturn(new Settings(null, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    service.setBaseCurrency(EUR);

    verify(settingsRepository).updateBaseCurrency(EUR);
  }

  @Test
  void refusesToOverwriteAlreadySetBaseCurrency() {
    when(settingsRepository.load()).thenReturn(new Settings(EUR, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.setBaseCurrency(CHF))
        .withMessageContaining("write-once");

    verify(settingsRepository, never()).updateBaseCurrency(CHF);
  }

  @Test
  void baseCurrencyIsEmptyOnFreshBook() {
    when(settingsRepository.load()).thenReturn(new Settings(null, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    assertThat(service.baseCurrency()).isEmpty();
  }

  @Test
  void baseCurrencyIsPresentOnceSet() {
    when(settingsRepository.load()).thenReturn(new Settings(CHF, "Andrey"));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    assertThat(service.baseCurrency()).contains(CHF);
  }

  @Test
  void setsDisplayName() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    service.setDisplayName("Andrey");

    verify(settingsRepository).updateDisplayName("Andrey");
  }

  @Test
  void offersSeededCurrenciesAsChoices() {
    List<Currency> seeded =
        List.of(new Currency(CHF, 2, "CHF", "Swiss Franc"), new Currency(EUR, 2, "€", "Euro"));
    when(currencyRepository.findAll()).thenReturn(seeded);
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    assertThat(service.availableCurrencies()).isEqualTo(seeded);
  }

  @Test
  void aiConfigDefaultsModelAndFallsBackToEnvKey() {
    when(settingsRepository.loadAi())
        .thenReturn(new AiSettings(null, null, null, null, null, null));
    SettingsService service =
        new SettingsService(settingsRepository, currencyRepository, "env-key");

    AiSettings config = service.aiConfig();

    assertThat(config.model()).isEqualTo("claude-sonnet-5");
    assertThat(config.apiKey()).isEqualTo("env-key");
  }

  @Test
  void aiConfigPrefersStoredModelAndKeyOverEnv() {
    when(settingsRepository.loadAi())
        .thenReturn(new AiSettings("claude-opus-4-8", "db-key", null, null, null, null));
    SettingsService service =
        new SettingsService(settingsRepository, currencyRepository, "env-key");

    AiSettings config = service.aiConfig();

    assertThat(config.model()).isEqualTo("claude-opus-4-8");
    assertThat(config.apiKey()).isEqualTo("db-key");
  }

  @Test
  void blankApiKeySubmitLeavesStoredKeyUntouched() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    service.setAiApiKey("   ", false);

    verify(settingsRepository, never()).updateAiApiKey(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void clearApiKeyRemovesStoredKey() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    service.setAiApiKey(null, true);

    verify(settingsRepository).updateAiApiKey(null);
  }

  @Test
  void aiSettingsViewMasksStoredKeyToLast4() {
    when(settingsRepository.loadAi())
        .thenReturn(new AiSettings(null, "sk-secret-9876", null, null, null, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    AiSettingsView view = service.aiSettingsView();

    assertThat(view.keySet()).isTrue();
    assertThat(view.keyLast4()).isEqualTo("9876");
    assertThat(view.envKeyPresent()).isFalse();
  }

  @Test
  void aiSystemPromptIsNullWhenUnsetOrBlank() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    when(settingsRepository.loadAiSystemPrompt()).thenReturn(null);
    assertThat(service.aiSystemPrompt()).isNull();

    when(settingsRepository.loadAiSystemPrompt()).thenReturn("   ");
    assertThat(service.aiSystemPrompt()).isNull();
  }

  @Test
  void aiSystemPromptReturnsTheStoredOverride() {
    when(settingsRepository.loadAiSystemPrompt()).thenReturn("custom instructions");
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    assertThat(service.aiSystemPrompt()).isEqualTo("custom instructions");
  }

  @Test
  void setAiSystemPromptStoresNonBlankAndClearsBlank() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository, "");

    service.setAiSystemPrompt("edited");
    verify(settingsRepository).updateAiSystemPrompt("edited");

    service.setAiSystemPrompt("  ");
    verify(settingsRepository).updateAiSystemPrompt(null);
  }

  @Test
  void costOfSumsPerRateComponents() {
    AiSettings rates =
        new AiSettings(
            null,
            null,
            new BigDecimal("3.00"), // input, per MTok
            new BigDecimal("15.00"), // output
            new BigDecimal("3.75"), // cache write
            new BigDecimal("0.30")); // cache read

    // 1000·3 + 200·15 + 0 + 0 = 6000 tok-$/MTok → 0.006000
    assertThat(rates.costOf(1000, 200, 0, 0)).isEqualByComparingTo("0.006000");
  }
}
