package volkovandr.hauptbuch.analytics;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the main page's Frame ({@link MainFrameController}),
 * reporting.md §11 / plan stage c — the default net worth over time render, the picker switching to
 * a different Preset and persisting it, the no-base-currency message, and a stale/emptied Frame
 * degrading rather than 500ing (the plan's "Frame whose Report was deleted" bar, exercised here
 * with a hand-seeded unknown slug since Presets themselves are non-deletable). {@code
 * landing.html}'s lazy-load hook is asserted alongside the Balances panel's own gate ({@code
 * LandingBalancesPanelIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class MainFrameControllerIntegrationTest {

  private static final String PATH = "/overview/main-frame";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private void seedFrameSlug(String slug) {
    jdbcClient
        .sql(
            "update layout_frame set preset_slug = :slug "
                + "where layout_id = 1 and row_position = 0 and col_position = 0")
        .param("slug", slug)
        .update();
  }

  private void seedOpeningBalance() {
    long opening =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('Opening Balances', "
                    + "'equity', 'EUR') returning account_id")
            .query(Long.class)
            .single();
    long cash =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('Cash', 'asset', 'EUR') "
                    + "returning account_id")
            .query(Long.class)
            .single();
    long txn =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (:d, 'confirmed') "
                    + "returning transaction_id")
            .param("d", LocalDate.now().minusDays(1))
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txn)
        .param("a", opening)
        .param("amt", new BigDecimal("-1000.00"))
        .update();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txn)
        .param("a", cash)
        .param("amt", new BigDecimal("1000.00"))
        .update();
  }

  @Test
  void defaultsToNetWorthOverTime() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedOpeningBalance();

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("<svg"),
                        containsString("selected"),
                        containsString("Net worth over time"))));
  }

  @Test
  void pickerSwitchesToDifferentPresetAndPersistsIt() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(post(PATH).param("presetSlug", "balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(allOf(containsString("<table"), not(containsString("<svg")))));

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void showsTheSetBaseCurrencyPromptBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")))
        .andExpect(content().string(not(containsString("<svg"))));
  }

  @Test
  void staleFrameReferenceDegradesToEmptyFrameRatherThanFiveHundreding() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("no-such-preset-any-more");

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No report configured")))
        // No real Preset is left wrongly looking selected in the picker (a browser defaults to the
        // first <option> when none is marked selected) — the placeholder holds that spot instead.
        .andExpect(content().string(containsString("Choose a report")));
  }

  @Test
  void switchingToTableWithTotalsRendersThem() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(post(PATH).param("presetSlug", "category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(allOf(containsString("<th>Total</th>"), containsString("<td>Total</td>"))));
  }

  @Test
  void landingMountsTheLazyLoadContainerOnceTheBaseCurrencyIsSet() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(PATH)))
        .andExpect(content().string(containsString("hx-trigger=\"load\"")));
  }

  @Test
  void landingOmitsTheContainerBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(PATH))));
  }
}
