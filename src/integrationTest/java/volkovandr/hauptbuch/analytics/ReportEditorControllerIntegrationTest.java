package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
 * Integration tier (CLAUDE.md §6): {@code /reports/new} — a not-yet-saved Report's own page,
 * starting from the category × month matrix spec (reporting.md §11a.1, plan stage d3) — and {@code
 * POST /reports/save-as-new}, the one shared "Save as new report" endpoint every editor page posts
 * to ({@link ReportController}, {@link SavedReportController} and this page alike).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportEditorControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ReportService reportService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;
  @Autowired ReportEngine reportEngine;

  private long insertAccount(String name, String type, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) values (:n, :t, 'EUR', :p) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void postToday(long from, long to, String amount) {
    long txn =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (:d, 'confirmed') "
                    + "returning transaction_id")
            .param("d", LocalDate.now())
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
  void newReportShowsThePromptBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/reports/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")));
  }

  @Test
  void newReportStartsFromTheCategoryMonthMatrixSpecWithNoUnsavedMarkerOrDeleteButton()
      throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Save as new report")))
        .andExpect(content().string(not(containsString("Unsaved changes"))))
        .andExpect(content().string(not(containsString("Delete report"))))
        // The matrix Preset's own scope — proof the default spec, not an empty one, is showing.
        .andExpect(content().string(containsString("Expense, Income")));
  }

  @Test
  void draftInTheQueryStringOverridesTheDefaultSpecAndShowsTheUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());

    mockMvc
        .perform(get("/reports/new").params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unsaved changes")))
        .andExpect(content().string(containsString("Asset, Equity, Liability")));
  }

  @Test
  void saveAsNewCreatesReportAndRedirectsToIt() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(Presets.balanceSheet());
    params.add("name", "My new report");
    params.add("renderer", "TABLE");
    params.add("trendLine", "false");

    String redirect =
        Objects.requireNonNull(
            mockMvc
                .perform(post("/reports/save-as-new").params(params))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/reports/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl());

    long reportId = Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
    SavedReport saved = reportService.find(reportId).orElseThrow();
    assertThat(saved.name()).isEqualTo("My new report");
    assertThat(saved.spec()).isEqualTo(Presets.balanceSheet());
    assertThat(saved.renderer()).isEqualTo(Renderer.TABLE);
    assertThat(saved.trendLine()).isFalse();
  }

  @Test
  void newReportTogglesRowsWithoutBecomingAnUnsavedDraft() throws Exception {
    // Issue 02: expanding a row on an untouched /reports/new carries only the expansion, so the
    // page keeps its default spec and shows no Unsaved marker.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", null);
    long food = insertAccount("Food", "expense", null);
    long bakery = insertAccount("Bakery", "expense", food);
    postToday(cash, bakery, "20.00");

    mockMvc
        .perform(get("/reports/new"))
        .andExpect(
            content().string(containsString("hx-get=\"/reports/new?expanded=" + food + "\"")))
        .andExpect(content().string(not(containsString("report__toggle--static"))));

    mockMvc
        .perform(get("/reports/new").param(RowToggle.EXPANDED, String.valueOf(food)))
        .andExpect(content().string(containsString("padding-left: 20px")))
        .andExpect(content().string(not(containsString("Unsaved changes"))));
  }

  @Test
  void newReportDraftKeepsItsExpansionThroughSettingsChange() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", null);
    long food = insertAccount("Food", "expense", null);
    long bakery = insertAccount("Bakery", "expense", food);
    postToday(cash, bakery, "20.00");
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    draft.set("rowTotals", String.valueOf(!Presets.categoryMonthMatrix().rowTotals()));
    draft.add(RowToggle.EXPANDED, String.valueOf(food));

    mockMvc
        .perform(get("/reports/new").params(draft))
        .andExpect(content().string(containsString("Unsaved changes")))
        .andExpect(content().string(containsString("padding-left: 20px")));
  }

  @Test
  void saveAsNewKeepsTheDraftsExpansion() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.add(RowToggle.EXPANDED, "7");
    params.add("name", "Expanded");
    params.add("renderer", "TABLE");
    params.add("trendLine", "false");

    String redirect =
        Objects.requireNonNull(
            mockMvc
                .perform(post("/reports/save-as-new").params(params))
                .andReturn()
                .getResponse()
                .getRedirectedUrl());

    long reportId = Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
    assertThat(reportService.find(reportId).orElseThrow().expandedNodeKeys()).containsExactly("7");
  }

  // ── ticked nodes as the axis's top level (reporting issue 08) ───────────────────────────────

  private static ReportSpec accountRows(Scope scope, List<ReportFilter> filters) {
    return new ReportSpec(
        List.of(Dimension.ACCOUNT),
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        scope,
        filters,
        DateRange.yearToDate(),
        false,
        false,
        true);
  }

  @Test
  void tickedAccountsAreTheAxisTopLevelWithoutTheirParents() throws Exception {
    // The owner's example: tick Cash-EUR and BankAaa (a parent) under "amounts booked to" — the
    // rows are exactly those two, Cash-EUR under its full path, and no Cash row.
    settingsService.setBaseCurrency("EUR");
    long opening = insertAccount("Opening Balances", "equity", null);
    long cash = insertAccount("Cash", "asset", null);
    long cashEur = insertAccount("Cash-EUR", "asset", cash);
    long cashUsd = insertAccount("Cash-USD", "asset", cash);
    long bankAaa = insertAccount("BankAaa", "asset", null);
    long checking = insertAccount("Checking", "asset", bankAaa);
    postToday(opening, cashEur, "20.00");
    postToday(opening, cashUsd, "7.00");
    postToday(opening, checking, "30.00");
    ReportSpec spec =
        accountRows(
            Scope.ofTypes("asset"),
            List.of(
                new ReportFilter(
                    FilterField.ACCOUNT,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(cashEur), String.valueOf(bankAaa)))));

    assertThat(reportEngine.render(spec).rows())
        .extracting(AxisNode::label)
        .containsExactly("BankAaa", "Cash:Cash-EUR");
    mockMvc
        .perform(get("/reports/new").params(ReportSpecQueryString.toParams(spec)))
        .andExpect(content().string(containsString("Cash:Cash-EUR")));
  }

  @Test
  void accountRowsNeverListCategoriesWhateverTheScope() {
    // reporting.md §4: an expense-only scope with Account rows explains the mismatch rather than
    // listing categories as accounts.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", null);
    long food = insertAccount("Food", "expense", null);
    postToday(cash, food, "20.00");

    ReportGrid grid = reportEngine.render(accountRows(Scope.ofTypes("expense"), List.of()));

    assertThat(grid.rows()).isEmpty();
    assertThat(grid.refusalMessage()).contains("Account covers asset");
  }
}
