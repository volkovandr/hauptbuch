package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the stage-5 settings screen and greeting landing, driven through
 * the controllers against real Postgres. This is the stage's acceptance surface — it proves the
 * first-run base-currency gate and the write-once guard end to end (a locked base currency cannot
 * be changed through the UI), and that a display name set in settings reaches the greeting.
 *
 * <p>{@code @Transactional} rolls each test back so the shared single-row {@code settings} table
 * stays clean across the reused container (plan §15).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingsScreenIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void freshBookOffersTheBaseCurrencyChooser() throws Exception {
    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        // The seeded currencies are offered as choices.
        .andExpect(content().string(containsString("Set base currency")))
        .andExpect(content().string(containsString("EUR — Euro")))
        .andExpect(content().string(containsString("CHF — Swiss Franc")));
  }

  @Test
  void settingBaseCurrencyLocksItReadOnly() throws Exception {
    mockMvc
        .perform(post("/settings/base-currency").param("baseCurrency", "EUR"))
        .andExpect(status().isOk())
        // Locked: the chooser is gone, the value shown read-only.
        .andExpect(content().string(not(containsString("Set base currency"))))
        .andExpect(content().string(containsString("immutable")))
        .andExpect(content().string(containsString("EUR")));

    // And a re-fetch of the screen keeps it locked.
    mockMvc
        .perform(get("/settings"))
        .andExpect(content().string(not(containsString("Set base currency"))));
  }

  @Test
  void baseCurrencyIsWriteOnceEvenAgainstDirectPost() throws Exception {
    mockMvc
        .perform(post("/settings/base-currency").param("baseCurrency", "EUR"))
        .andExpect(status().isOk());

    // The UI removes the chooser once locked (proven above); even a hand-crafted POST that bypasses
    // it hits the write-once guard (data-model §3.8), which surfaces as the guard's exception
    // (wrapped by the servlet container as it escapes the handler).
    assertThatThrownBy(
            () -> mockMvc.perform(post("/settings/base-currency").param("baseCurrency", "CHF")))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("write-once");
  }

  @Test
  void displayNameSetInSettingsShowsInTheGreeting() throws Exception {
    mockMvc
        .perform(post("/settings/display-name").param("displayName", "Andrey"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/")).andExpect(content().string(containsString("Hello, Andrey")));
  }

  @Test
  void freshBookGreetsNeutrallyAndPointsAtSettings() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Welcome")))
        // No base currency yet: the first-run prompt to open settings is shown.
        .andExpect(content().string(containsString("Set the base currency")));
  }

  // ── AI parsing section (stage 9e, data-model §3.8) ──────────────────────────

  @Test
  void aiSectionRendersWithTheModelDefaultAndNoKey() throws Exception {
    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AI parsing")))
        // The default model is offered as a placeholder; no key stored on a fresh book.
        .andExpect(content().string(containsString("claude-sonnet-5")))
        .andExpect(content().string(containsString("No key stored")));
  }

  @Test
  void savingTheKeyShowsItMaskedNotVerbatim() throws Exception {
    mockMvc
        .perform(post("/settings/ai-key").param("aiApiKey", "sk-secret-9876"))
        .andExpect(status().isOk())
        // Write-only: the stored key is never rendered back — only its last four characters.
        .andExpect(content().string(not(containsString("sk-secret-9876"))))
        .andExpect(content().string(containsString("…9876")));
  }

  @Test
  void savingModelAndPricesRoundTripsThroughTheScreen() throws Exception {
    mockMvc
        .perform(post("/settings/ai-model").param("aiModel", "claude-opus-4-8"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/settings/ai-prices")
                .param("priceIn", "3.00")
                .param("priceOut", "15.00")
                .param("priceCacheWrite", "3.75")
                .param("priceCacheRead", "0.30"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/settings"))
        .andExpect(content().string(containsString("claude-opus-4-8")));
  }

  @Test
  void priceFieldsAcceptCommaDecimalSeparator() throws Exception {
    // Owner feedback 2026-08-02: a comma must not 400 — the register's amount inputs tolerate both
    // separators, and so must these. "2,00" parses to 2.00, same as "2.00".
    mockMvc
        .perform(
            post("/settings/ai-prices")
                .param("priceIn", "2,00")
                .param("priceOut", "10,50")
                .param("priceCacheWrite", "")
                .param("priceCacheRead", ""))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/settings"))
        .andExpect(content().string(containsString("2.00")))
        .andExpect(content().string(containsString("10.50")));
  }
}
