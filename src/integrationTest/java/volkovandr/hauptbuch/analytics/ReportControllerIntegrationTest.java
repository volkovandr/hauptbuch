package volkovandr.hauptbuch.analytics;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the four Presets' own pages and editor ({@link
 * ReportController}, reporting.md §11a, plan stage d3) rendered against real Postgres — every
 * Preset renders correctly against real data, the chart/table swap works, and a draft in the query
 * string overrides a Preset's own spec with the Unsaved marker shown. The reporting page itself is
 * {@link ReportsLayoutControllerIntegrationTest}'s job; "Save as new report" (the one bridge from a
 * Preset to an owned Report) is {@link ReportEditorControllerIntegrationTest}'s job, since it is
 * one shared endpoint every editor page posts to. The renderers' own SVG-well-formedness and the
 * pie's negative-measure refusal are {@link ChartSvgWriterTest}/{@link ChartViewAssemblerTest}'s
 * job (CLAUDE.md §6 — no DB dependency, so the unit tier, not here); neither shipped Preset uses
 * the pie renderer (reporting.md §16).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private long insertAccount(String name, String type, String currency, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) values (:n, :t, :c, :p) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void postSingleCurrency(long from, long to, LocalDate date, String amount) {
    long txn =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (:d, 'confirmed') "
                    + "returning transaction_id")
            .param("d", date)
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txn)
        .param("a", from)
        .param("amt", new BigDecimal(amount).negate())
        .update();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txn)
        .param("a", to)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  @Test
  void presetShowsTheSetBaseCurrencyPromptBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")));
  }

  @Test
  void anUnknownPresetSlugIs404() throws Exception {
    settingsService.setBaseCurrency("EUR");
    mockMvc.perform(get("/reports/preset/no-such-preset")).andExpect(status().isNotFound());
  }

  @Test
  void categoryMonthMatrixRendersFoodAsTopLevelRowWithThisMonthsSpend() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    postSingleCurrency(cash, food, LocalDate.now(), "42.50");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Category")))
        .andExpect(content().string(containsString("Food")))
        .andExpect(content().string(containsString("42,50")));
  }

  @Test
  void balanceSheetRendersTheAccountsCurrentClosingBalance() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long opening = insertAccount("Opening Balances", "equity", "EUR", null);
    long cash = insertAccount("Cash", "asset", "EUR", null);
    postSingleCurrency(opening, cash, LocalDate.now().minusDays(1), "1000.00");

    mockMvc
        .perform(get("/reports/preset/balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Cash")))
        .andExpect(content().string(containsString("1.000,00")));
  }

  @Test
  void netWorthOverTimeRendersLineChartWithTrendOverlay() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long opening = insertAccount("Opening Balances", "equity", "EUR", null);
    long cash = insertAccount("Cash", "asset", "EUR", null);
    // Two postings in different months so the trend line has more than one point to fit — a
    // single recent posting would leave every earlier month blank (no balance on record yet).
    postSingleCurrency(opening, cash, LocalDate.now().minusMonths(3), "500.00");
    postSingleCurrency(opening, cash, LocalDate.now().minusDays(1), "500.00");

    mockMvc
        .perform(get("/reports/preset/net-worth-over-time"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(containsString("chart-trend")))
        .andExpect(content().string(containsString("Show table")));
  }

  @Test
  void thisMonthVsLastRendersGroupedBarChart() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    postSingleCurrency(cash, food, LocalDate.now(), "42.50");

    mockMvc
        .perform(get("/reports/preset/this-month-vs-last"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(containsString("Food")));
  }

  @Test
  void chartSwapsToItsTableFragmentAndBack() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long opening = insertAccount("Opening Balances", "equity", "EUR", null);
    long cash = insertAccount("Cash", "asset", "EUR", null);
    postSingleCurrency(opening, cash, LocalDate.now().minusDays(1), "1000.00");

    mockMvc
        .perform(get("/reports/preset/net-worth-over-time/view").param("view", "table"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")))
        .andExpect(content().string(containsString("Show chart")))
        .andExpect(content().string(not(containsString("<svg"))))
        // hx-replace-url on the toggle (reporting.md §11a.1) keeps the address bar current —
        // here it is unchanged, since there is no draft to preserve across the swap.
        .andExpect(
            content()
                .string(containsString("hx-replace-url=\"/reports/preset/net-worth-over-time\"")));

    mockMvc
        .perform(get("/reports/preset/net-worth-over-time/view").param("view", "chart"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(containsString("Show table")));
  }

  @Test
  void tableOnlyPresetOffersNoChartToggle() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Show chart"))));
  }

  @Test
  void tableOnlyPresetsViewFragmentClampsToTableEvenIfAskedForChart() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix/view").param("view", "chart"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void presetRendersItsOwnActionsWithNoUnsavedMarkerAndNoSaveOrDeleteButton() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Save as new report")))
        .andExpect(content().string(not(containsString("Unsaved changes"))))
        .andExpect(content().string(not(containsString("Delete report"))));
  }

  @Test
  void draftInTheQueryStringOverridesThePresetsOwnSpecAndShowsTheUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());

    mockMvc
        .perform(get("/reports/preset/category-month-matrix").params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unsaved changes")))
        // The balance sheet's own scope ("Asset, Equity, Liability") rather than the matrix
        // Preset's ("Expense, Income") is the proof the draft, not the Preset's own spec, rendered.
        .andExpect(content().string(containsString("Asset, Equity, Liability")));
  }

  @Test
  void discardChangesLinksBackToThePresetsBareUrl() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());

    mockMvc
        .perform(get("/reports/preset/category-month-matrix").params(draft))
        .andExpect(
            content().string(containsString("href=\"/reports/preset/category-month-matrix\"")));
  }
}
