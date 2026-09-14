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
 * reporting.md §11 / plan stage d2 — the default net worth over time render (heading + rendering,
 * no picker inline), the picker (now its own lazy-loaded fragment) switching to a different Preset
 * and persisting it, the no-base-currency message, and a stale/emptied Frame reference hiding the
 * Frame entirely rather than showing a "No report" placeholder (reporting.md §11's main-page rule)
 * or 500ing. {@code landing.html}'s two lazy-load hooks (the Frame, and the picker below the
 * Balances panel) are asserted alongside the Balances panel's own gate ({@code
 * LandingBalancesPanelIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class MainFrameControllerIntegrationTest {

  private static final String PATH = "/overview/main-frame";
  private static final String PICKER_PATH = PATH + "/picker";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;
  @Autowired ReportService reportService;

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
            content().string(allOf(containsString("<svg"), containsString("Net worth over time"))));
  }

  @Test
  void frameCarriesNoPickerInline() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedOpeningBalance();

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("<select"))));
  }

  /**
   * landing.html's own container owns {@code id="main-frame"} (asserted in {@code
   * landingsOwnMainFrameContainerCarriesTheIdBeforeAnyLazyLoadResolves}); this GET's response is
   * swapped into that container's innerHTML, so it must not carry the id itself — a second element
   * with the same id would make the picker's retarget ambiguous.
   */
  @Test
  void frameResponseCarriesNoIdOfItsOwn() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedOpeningBalance();

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("id=\"main-frame\""))));
  }

  @Test
  void pickerSwitchesToDifferentPresetAndPersistsIt() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(post(PATH).param("selection", "preset:balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(allOf(containsString("<table"), not(containsString("<svg")))));

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void pickerSwitchesToSavedReportAndPersistsIt() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post(PATH).param("selection", "report:" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/reports/" + saved.reportId() + "\"")));
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
  void configuredFrameLinksToThePresetsOwnPageWhereCopyToMyReportsLives() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedOpeningBalance();

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("href=\"/reports/preset/net-worth-over-time\"")));
  }

  @Test
  void staleFrameReferenceHidesTheFrameEntirely() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("no-such-preset-any-more");

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        not(containsString("No report")),
                        not(containsString("class=\"panel frame\"")))));
  }

  @Test
  void pickerShowsThePlaceholderWhenTheFrameIsUnconfigured() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("no-such-preset-any-more");

    mockMvc
        .perform(get(PICKER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Choose a report")));
  }

  @Test
  void pickerReflectsThePersistedSelection() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get(PICKER_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("value=\"preset:net-worth-over-time\""),
                        not(containsString("Choose a report")))));
  }

  @Test
  void switchingToTableWithTotalsRendersThem() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(post(PATH).param("selection", "preset:category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(allOf(containsString("<th>Total</th>"), containsString("<td>Total</td>"))));
  }

  @Test
  void landingMountsBothLazyLoadContainersOnceTheBaseCurrencyIsSet() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(PATH)))
        .andExpect(content().string(containsString(PICKER_PATH)))
        .andExpect(content().string(containsString("hx-trigger=\"load\"")));
  }

  /**
   * The picker's POST retargets {@code #main-frame} by id — that id must already be in the DOM at
   * first paint (owned by landing.html's own container), not only after the Frame's own {@code
   * hx-get} resolves, or a selection made in that window would persist server-side but never swap
   * into anything (no htmx target found).
   */
  @Test
  void landingsOwnMainFrameContainerCarriesTheIdBeforeAnyLazyLoadResolves() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"main-frame\"")));
  }

  @Test
  void landingOmitsBothContainersBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(PATH))))
        .andExpect(content().string(not(containsString(PICKER_PATH))));
  }
}
