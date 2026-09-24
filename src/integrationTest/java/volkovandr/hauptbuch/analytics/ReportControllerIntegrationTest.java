package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the four Presets' own pages and editor ({@link
 * ReportController}, reporting.md §11a, plan stage d3) rendered against real Postgres — every
 * Preset renders correctly against real data, an htmx request gets back just the {@code
 * #report-page} fragment while a plain request gets the full shell, the settings strip renders its
 * groups, switching the Renderer control changes what is drawn, and a draft in the query string
 * overrides a Preset's own spec with the Unsaved marker shown. The reporting page itself is {@link
 * ReportsLayoutControllerIntegrationTest}'s job; "Save as new report" (the one bridge from a Preset
 * to an owned Report) is {@link ReportEditorControllerIntegrationTest}'s job, since it is one
 * shared endpoint every editor page posts to. The renderers' own SVG-well-formedness and the pie's
 * negative-measure refusal are {@link ChartSvgWriterTest}/{@link ChartViewAssemblerTest}'s job
 * (CLAUDE.md §6 — no DB dependency, so the unit tier, not here); neither shipped Preset uses the
 * pie renderer (reporting.md §16).
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
        .andExpect(content().string(containsString("42,50")))
        .andExpect(
            content().string(not(containsString("help__text\" aria-hidden=\"true\"></span>"))));
  }

  @Test
  void presetTogglesRowsWithoutPersistingOrBecomingDraft() throws Exception {
    // Issue 02: a Preset can never be saved over, so its expansion lives only in the URL.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "hx-get=\"/reports/preset/category-month-matrix?expanded=" + food + "\"")));

    mockMvc
        .perform(
            get("/reports/preset/category-month-matrix")
                .param(RowToggle.EXPANDED, String.valueOf(food)))
        .andExpect(content().string(containsString("padding-left: 20px")))
        .andExpect(content().string(not(containsString("Unsaved changes"))));
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
        .andExpect(content().string(containsString("1.000,00")))
        // Regression guard (§11a.7): a legal cell must render with no help marker at all, not one
        // with an empty tooltip — a same-tag th:if/th:replace combination on report-table-body.html
        // once rendered exactly this for every legal cell (fixed during plan stage d3-5).
        .andExpect(
            content().string(not(containsString("help__text\" aria-hidden=\"true\"></span>"))));
  }

  @Test
  void balanceSheetAccountCurrencyCellSpanningTwoCurrenciesShowsHelpMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long opening = insertAccount("Opening Balances", "equity", "EUR", null);
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long cashEur = insertAccount("Cash-EUR", "asset", "EUR", cash);
    long cashChf = insertAccount("Cash-CHF", "asset", "CHF", cash);
    postSingleCurrency(opening, cashEur, LocalDate.now().minusDays(1), "500.00");
    postSingleCurrency(opening, cashChf, LocalDate.now().minusDays(1), "300.00");

    mockMvc
        .perform(get("/reports/preset/balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("—")))
        .andExpect(content().string(containsString("class=\"help\"")))
        .andExpect(content().string(containsString("more than one native currency")));
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
        .andExpect(content().string(containsString("Renderer")));
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
  void anHtmxRequestGetsBackJustTheReportPageFragmentNotTheShell() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"report-page\"")))
        .andExpect(content().string(not(containsString("app-bar__brand"))));
  }

  @Test
  void plainRequestGetsTheFullPageShell() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("app-bar__brand")))
        .andExpect(content().string(containsString("id=\"report-page\"")));
  }

  @Test
  void theSettingsStripRendersEveryGroup() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rows &amp; columns")))
        .andExpect(content().string(containsString("Measures")))
        .andExpect(content().string(containsString("Scope")))
        .andExpect(content().string(containsString("Filters")))
        .andExpect(content().string(containsString("Date range")))
        .andExpect(content().string(containsString("Display")))
        .andExpect(content().string(containsString("Renderer")));
  }

  @Test
  void theFiltersGroupRendersEveryFixedFieldSection() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">Category<")))
        .andExpect(content().string(containsString(">Account<")))
        .andExpect(content().string(containsString(">Tag<")))
        .andExpect(content().string(containsString(">Payee<")))
        .andExpect(content().string(containsString(">Person<")))
        .andExpect(content().string(containsString(">Currency<")))
        .andExpect(content().string(containsString(">Account type<")))
        .andExpect(content().string(containsString(">Reconciliation<")))
        .andExpect(content().string(containsString(">Note text<")))
        .andExpect(content().string(containsString("only transactions touching")))
        .andExpect(content().string(containsString("only amounts booked to")))
        .andExpect(content().string(containsString("is one of")))
        .andExpect(content().string(containsString("matches (regular expression)")));
  }

  @Test
  void tickingCategoryNodeRendersItsChildTickedAndDisabled() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long food = insertAccount("Food", "expense", "EUR", null);
    final long bakery = insertAccount("Bakery", "expense", "EUR", food);

    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    draft.add("filterField", "CATEGORY");
    draft.add("filter.CATEGORY.level", "POSTING");
    draft.add("filter.CATEGORY.op", "IS_ONE_OF");
    draft.add("filter.CATEGORY.value", String.valueOf(food));

    MvcResult result =
        mockMvc
            .perform(get("/reports/preset/category-month-matrix").params(draft))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();

    // Food itself: ticked, still interactive (not disabled) — the explicitly-stored node. A
    // top-level node's own data-filter-ancestors is omitted (Thymeleaf drops an empty th:attr
    // value), which filter-groups.js's node mode already tolerates client-side.
    String foodTag = filterCheckboxTag(body, food);
    assertThat(foodTag).contains("checked=\"checked\"").doesNotContain("disabled=\"disabled\"");
    // Bakery: covered by Food's own subtree rule — ticked AND disabled, its own id never submitted.
    String bakeryTag = filterCheckboxTag(body, bakery);
    assertThat(bakeryTag)
        .contains("checked=\"checked\"")
        .contains("disabled=\"disabled\"")
        .contains("data-filter-ancestors=\"" + food + "\"");
  }

  @Test
  void accountFilterOffersOnePersonalDebtsEntryInsteadOfTheCosmeticLeaves() throws Exception {
    // Reporting issue 11: the debt leaves' own names (personal.<CUR>) say nothing about whose
    // they are; the filter offers one "Personal debts" entry that stands for all of them.
    settingsService.setBaseCurrency("EUR");
    long leaf = insertAccount("personal.EUR", "asset", "EUR", null);
    jdbcClient
        .sql("update account set person_leaf = true where account_id = :a")
        .param("a", leaf)
        .update();
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());
    draft.add("filterField", "ACCOUNT");
    draft.add("filter.ACCOUNT.level", "TRANSACTION");
    draft.add("filter.ACCOUNT.op", "IS_ONE_OF");
    draft.add("filter.ACCOUNT.value", "personal");

    String body =
        mockMvc
            .perform(get("/reports/preset/balance-sheet").params(draft))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("personal.EUR");
    assertThat(filterCheckboxTag(body, "personal"))
        .contains("checked=\"checked\"")
        .doesNotContain("disabled=\"disabled\"");
    assertThat(body).contains("Personal debts");
  }

  @Test
  void filteredSectionOffersResetThatKeepsEveryOtherFilter() throws Exception {
    // Reporting issue 21: one click clears a section's own filter; the rest of the draft stays.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());
    draft.add("filterField", "ACCOUNT");
    draft.add("filter.ACCOUNT.level", "TRANSACTION");
    draft.add("filter.ACCOUNT.op", "IS_ONE_OF");
    draft.add("filter.ACCOUNT.value", String.valueOf(cash));
    draft.add("filterField", "PAYEE");
    draft.add("filter.PAYEE.level", "TRANSACTION");
    draft.add("filter.PAYEE.op", "MATCHES");
    draft.add("filter.PAYEE.matches", "shop");
    draft.add("filterField", "CURRENCY");
    draft.add("filter.CURRENCY.level", "POSTING");
    draft.add("filter.CURRENCY.op", "IS_ONE_OF");
    draft.add("filter.CURRENCY.value", "EUR");

    String body =
        mockMvc
            .perform(get("/reports/preset/balance-sheet").params(draft))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // A hierarchy section's reset keeps the Payee and Currency filters, but not its own.
    assertThat(resetForm(body, "ACCOUNT"))
        .containsPattern("name=\"filter.PAYEE.matches\"\\s+value=\"shop\"")
        .containsPattern("name=\"filter.CURRENCY.value\"\\s+value=\"EUR\"")
        .doesNotContain("filter.ACCOUNT.");
    // Payee and a flat option section get their own, which keep the Account filter.
    assertThat(resetForm(body, "PAYEE"))
        .contains("name=\"filter.ACCOUNT.value\"")
        .doesNotContain("filter.PAYEE.");
    assertThat(resetForm(body, "CURRENCY"))
        .contains("name=\"filter.ACCOUNT.value\"")
        .doesNotContain("filter.CURRENCY.");
    assertThat(body).contains("form=\"filter-reset-ACCOUNT\"");
    // An empty section has nothing to reset.
    assertThat(body).doesNotContain("filter-reset-CATEGORY").doesNotContain("filter-reset-NOTE");
  }

  /** The Reset form of one filter section, from its opening tag to its close. */
  private static String resetForm(String body, String field) {
    Matcher matcher =
        Pattern.compile("(?s)<form[^>]*id=\"filter-reset-" + field + "\".*?</form>").matcher(body);
    if (!matcher.find()) {
      throw new AssertionError("No reset form for " + field);
    }
    return matcher.group();
  }

  @Test
  void payeeMatchesOperatorRendersItsRegexAndTicksTheMatchesRadio() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    draft.add("filterField", "PAYEE");
    draft.add("filter.PAYEE.level", "TRANSACTION");
    draft.add("filter.PAYEE.op", "MATCHES");
    draft.add("filter.PAYEE.matches", "(?i)shop.*");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix").params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"(?i)shop.*\"")))
        .andExpect(
            content()
                .string(
                    matchesRegex(
                        "(?s).*name=\"filter.PAYEE.op\"\\s+value=\"MATCHES\"\\s+"
                            + "class=\"payee-mode-matches\"\\s+checked=\"checked\".*")));
  }

  @Test
  void currencyFilterSectionDefaultsToPostingLevelWhenNoFilterIsSet() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/preset/category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    matchesRegex(
                        "(?s).*name=\"filter.CURRENCY.level\"\\s+value=\"POSTING\"\\s+"
                            + "checked=\"checked\".*")));
  }

  /**
   * The one hierarchy-tree checkbox for a node id — anchored on {@code data-filter-node}, never a
   * hidden passthrough field (every <em>other</em> filter section's own {@code <form>} resubmits
   * this node's ticked value as a plain hidden field too, since it does not own that filter).
   */
  private static String filterCheckboxTag(String body, long nodeId) {
    return filterCheckboxTag(body, String.valueOf(nodeId));
  }

  private static String filterCheckboxTag(String body, String nodeId) {
    Matcher matcher =
        Pattern.compile("<input[^>]*data-filter-node=\"" + nodeId + "\"[^>]*/>").matcher(body);
    if (!matcher.find()) {
      throw new AssertionError("No <input data-filter-node=\"" + nodeId + "\"> tag found");
    }
    return matcher.group();
  }

  @Test
  void switchingTheRendererToLineRendersTheChartInstead() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", Presets.netWorthOverTime(), Renderer.LINE, false));

    mockMvc
        .perform(get("/reports/preset/category-month-matrix").params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(containsString("Unsaved changes")));
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
