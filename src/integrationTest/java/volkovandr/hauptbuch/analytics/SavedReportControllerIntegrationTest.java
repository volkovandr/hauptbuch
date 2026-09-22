package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Integration tier (CLAUDE.md §6): a saved Report's own page and editor (reporting.md §14/§11a,
 * plan stage d3) — a draft URL renders its spec with the Unsaved marker, no parameters renders the
 * saved spec, Save overwrites in place, Discard points back to the bare URL, and Delete removes the
 * row. "Save as new report" is {@link ReportEditorControllerIntegrationTest}'s job, since it is one
 * shared endpoint every editor page posts to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SavedReportControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ReportService reportService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

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
  void savedReportShowsThePromptBeforeTheBaseCurrencyIsSet() throws Exception {
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")));
  }

  @Test
  void savedReportRendersItsOwnNameAndActionsWithNoUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("My matrix")))
        .andExpect(content().string(containsString("Save")))
        .andExpect(content().string(containsString("Save as new report")))
        .andExpect(content().string(containsString("Delete report")))
        .andExpect(content().string(not(containsString("Unsaved changes"))));
  }

  @Test
  void draftInTheQueryStringOverridesTheSavedSpecAndShowsTheUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unsaved changes")))
        // The matrix's own scope ("Expense, Income") rather than the balance sheet's ("Asset,
        // Equity, Liability") is the proof the draft, not the saved spec, rendered.
        .andExpect(content().string(containsString("Expense, Income")));
  }

  @Test
  void discardChangesLinksBackToTheBareUrl() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(content().string(containsString("href=\"/reports/" + saved.reportId() + "\"")));
  }

  @Test
  void saveOverwritesTheSpecRendererAndTrendLineAndRedirectsBackToTheSameReport() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> newSpec =
        ReportSpecQueryString.toParams(Presets.netWorthOverTime());
    newSpec.add("name", "New name");
    newSpec.add("renderer", "LINE");
    newSpec.add("trendLine", "true");
    SavedReport saved =
        reportService.save("Old name", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/save").params(newSpec))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/reports/" + saved.reportId()));

    SavedReport updated = reportService.find(saved.reportId()).orElseThrow();
    assertThat(updated.name()).isEqualTo("New name");
    assertThat(updated.spec()).isEqualTo(Presets.netWorthOverTime());
    assertThat(updated.renderer()).isEqualTo(Renderer.LINE);
    assertThat(updated.trendLine()).isTrue();
  }

  @Test
  void deleteRemovesTheReport() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Disposable", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/reports"));

    assertThat(reportService.find(saved.reportId())).isEmpty();
  }

  @Test
  void anHtmxRequestGetsBackJustTheReportPageFragmentNotTheShell() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);

    mockMvc
        .perform(get("/reports/" + saved.reportId()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(not(containsString("app-bar__brand"))));
  }

  @Test
  void switchingTheRendererToTableRendersTheTableInstead() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", Presets.netWorthOverTime(), Renderer.TABLE, false));

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")))
        .andExpect(content().string(containsString("Unsaved changes")));
  }

  @Test
  void draftWithTrendLineExplicitlyFalseTurnsTheOverlayOff() throws Exception {
    // What the browser actually submits when the Trend line checkbox is unticked (report-settings
    // .html: a hidden trendLine=false fallback sits right after the checkbox so an unticked box's
    // own field, which a browser omits, does not leave the last-saved true value in place).
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", Presets.netWorthOverTime(), Renderer.LINE, false));

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("chart-trend"))));
  }

  // ── row-tree expand/collapse toggle (reporting.md §9.1/§9.2, plan stage e2) ─────────────────
  //
  // The filter panel lists every category leaf (Bakery included) regardless of the grid's own
  // expansion state, so a bare "not(containsString("Bakery"))" on the full page is contaminated —
  // the depth-1 child row's own indent style ("padding-left: 20px", report-table-body.html) is the
  // grid-specific signal instead. The toggle endpoint's own response is just the table fragment
  // (no filter panel), so "Bakery" alone is reliable there.

  @Test
  void collapsedByDefaultThenTogglingExpandsAndPersistsAcrossReload() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("Food")))
        .andExpect(content().string(not(containsString("padding-left: 20px"))));

    String expanded =
        mockMvc
            .perform(
                post("/reports/" + saved.reportId() + "/expand")
                    .param("node", String.valueOf(food)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(expanded).contains("Bakery").contains("padding-left: 20px");

    // Persisted: a fresh page load, no toggle involved, still shows the expanded child.
    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("padding-left: 20px")));
  }

  @Test
  void togglingTwiceCollapsesAgainAndPersists() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    mockMvc.perform(
        post("/reports/" + saved.reportId() + "/expand").param("node", String.valueOf(food)));

    String collapsedAgain =
        mockMvc
            .perform(
                post("/reports/" + saved.reportId() + "/expand")
                    .param("node", String.valueOf(food)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(collapsedAgain).doesNotContain("padding-left: 20px");

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(not(containsString("padding-left: 20px"))));
  }

  @Test
  void autoExpandsOnFreshPageLoadWhenFilterSelectsExactlyOneCategory() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    ReportSpec filteredToFood =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income", "expense"),
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(food)))),
            DateRange.yearToDate(),
            true,
            true,
            true);
    SavedReport saved = reportService.save("Just Food", filteredToFood, Renderer.TABLE, false);

    // Never toggled — auto's one-node rule (§9.2) expands it on the very first load.
    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("padding-left: 20px")));
  }
}
