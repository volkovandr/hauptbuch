package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;

/**
 * Integration tier (§1.5): the stage-9e settings AI section round-trips (data-model §3.8) — model,
 * the DB-stored key, and the four price rates write and read back on the single settings row.
 * Flyway applies V12; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingsAiRepositoryIntegrationTest {

  @Autowired SettingsRepository settingsRepository;

  @Test
  void aiColumnsDefaultToNullOnFreshBook() {
    AiSettings ai = settingsRepository.loadAi();

    assertThat(ai.model()).isNull();
    assertThat(ai.apiKey()).isNull();
    assertThat(ai.priceIn()).isNull();
  }

  @Test
  void modelKeyAndPricesRoundTrip() {
    settingsRepository.updateAiModel("claude-opus-4-8");
    settingsRepository.updateAiApiKey("sk-secret-1234");
    settingsRepository.updateAiPrices(
        new BigDecimal("3.00"),
        new BigDecimal("15.00"),
        new BigDecimal("3.75"),
        new BigDecimal("0.30"));

    AiSettings ai = settingsRepository.loadAi();
    assertThat(ai.model()).isEqualTo("claude-opus-4-8");
    assertThat(ai.apiKey()).isEqualTo("sk-secret-1234");
    assertThat(ai.priceIn()).isEqualByComparingTo("3.00");
    assertThat(ai.priceOut()).isEqualByComparingTo("15.00");
    assertThat(ai.priceCacheWrite()).isEqualByComparingTo("3.75");
    assertThat(ai.priceCacheRead()).isEqualByComparingTo("0.30");
  }

  @Test
  void clearingTheApiKeyStoresNull() {
    settingsRepository.updateAiApiKey("sk-secret-1234");
    settingsRepository.updateAiApiKey(null);

    assertThat(settingsRepository.loadAi().apiKey()).isNull();
  }

  @Test
  void systemPromptRoundTripsAndClears() {
    assertThat(settingsRepository.loadAiSystemPrompt()).isNull();

    settingsRepository.updateAiSystemPrompt("my custom instructions");
    assertThat(settingsRepository.loadAiSystemPrompt()).isEqualTo("my custom instructions");

    settingsRepository.updateAiSystemPrompt(null);
    assertThat(settingsRepository.loadAiSystemPrompt()).isNull();
  }
}
