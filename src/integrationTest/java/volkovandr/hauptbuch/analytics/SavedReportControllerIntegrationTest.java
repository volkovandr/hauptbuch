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
  @Autowired ReportEngine reportEngine;
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
  void settingsStripSwapsOnlyTheFrameAndGroupBodiesSoOpenGroupsStayOpen() throws Exception {
    // Owner finding (stage e): every settings change swapped the whole #report-page, re-rendering
    // each <details> group closed. Now a change swaps #report-frame and refreshes each group's body
    // out of band (hx-select-oob) — the <details> elements themselves, and so their open state, are
    // never replaced.
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    String page =
        mockMvc
            .perform(get("/reports/" + saved.reportId()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(page).doesNotContain("hx-target=\"#report-page\"");
    assertThat(page).contains("hx-select=\"#report-frame\"");
    assertThat(page).contains("hx-select-oob=\"" + ReportSettingsView.REFRESHED_REGIONS + "\"");
    for (String region : ReportSettingsView.REFRESHED_REGIONS.split(",")) {
      assertThat(page).contains("id=\"" + region.substring(1) + "\"");
    }
    // The date range's live endpoint-label requests must not inherit the form's hx-select.
    assertThat(page).contains("hx-disinherit=\"*\"");
  }

  @Test
  void rendererIsFourRadioButtonsWithTheSavedOneChecked() throws Exception {
    // Owner finding (stage e): the Renderer is a four-way choice, shown as radio-style buttons
    // rather than a dropdown (reporting.md §11a.2).
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);

    String page =
        mockMvc
            .perform(get("/reports/" + saved.reportId()))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+", " ");

    assertThat(page).doesNotContain("<select id=\"settings-renderer\"");
    for (String renderer : List.of("TABLE", "BAR", "PIE")) {
      assertThat(page).contains("type=\"radio\" name=\"renderer\" value=\"" + renderer + "\" />");
    }
    assertThat(page)
        .contains("type=\"radio\" name=\"renderer\" value=\"LINE\" checked=\"checked\"");
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

  private static ReportSpec tagRowsWithClosingBalance() {
    return new ReportSpec(
        List.of(Dimension.TAG),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.closingBalance(PresentationCurrency.BASE)),
        Scope.ofTypes("asset"),
        List.of(),
        DateRange.yearToDate(),
        false,
        false,
        true);
  }

  @Test
  void engineRefusalRendersItsReasonInPlaceOfTheTableNotAnError() throws Exception {
    // Issue 18: a closing balance by Tag reached the engine by URL and 500'd into the generic
    // error toast. The page now renders, the reason in place of the report.
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(
            get("/reports/" + saved.reportId())
                .params(ReportSpecQueryString.toParams(tagRowsWithClosingBalance())))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("A tag has no closing balance")))
        .andExpect(content().string(containsString("Unsaved changes")));
  }

  @Test
  void engineRefusalRendersItsReasonInPlaceOfTheChartNotAnError() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, false);
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", tagRowsWithClosingBalance(), Renderer.LINE, false));

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("A tag has no closing balance")));
  }

  @Test
  void dateLadderWeekQueryParamRendersWeekColumnHeaders() throws Exception {
    // The settings strip's own Date ladder <select> (reporting.md §8.2, stage e4) submits exactly
    // this "dateLadder" parameter — proves it reaches the engine end to end, not just that
    // ReportEngine itself honors the spec field (ReportEngineTest's own job).
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Weekly", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    ReportSpec weekLadder =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income", "expense"),
            List.of(),
            // A Monday-to-Sunday range (2026-01-05 is a Monday) — one full, unclipped week.
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 5)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 11))),
            false,
            false,
            true,
            false,
            DateLadder.WEEK);
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(weekLadder);

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("w/c 5 Jan 2026")));
  }

  @Test
  void suppressEmptyColumnsHidesAnAccountColumnTheFilterExcludesEntirely() throws Exception {
    // Reproduces the owner's report: Date on rows, Account on columns, a "touching Cash" filter —
    // Savings never shares a transaction with Cash, so its own column has no data at all and must
    // be hidden once suppressEmptyColumns is on (reporting.md §7.3's column-axis mirror), even
    // though topLevelAccounts lists it as a candidate regardless of the filter (by design, so it
    // can be suppressed rather than never listed).
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long savings = insertAccount("Savings", "asset", "EUR", null);
    long groceries = insertAccount("Groceries", "expense", "EUR", null);
    long fuel = insertAccount("Fuel", "expense", "EUR", null);
    postSingleCurrency(cash, groceries, LocalDate.of(2026, 1, 10), "20.00");
    postSingleCurrency(savings, fuel, LocalDate.of(2026, 1, 12), "15.00");
    SavedReport saved =
        reportService.save("Cash activity", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.DATE),
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("asset"),
            List.of(
                new ReportFilter(
                    FilterField.ACCOUNT,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(cash)))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            false,
            false,
            DateLadder.MONTH,
            true);
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(spec);

    // "Savings" still legitimately appears elsewhere on the page (the Filters group's own Account
    // section lists every account as a candidate to tick, suppression aside) — the column header
    // itself, a bare <th>Savings</th> (report-table-body.html has no other tag shaped that way), is
    // the grid-specific signal, mirroring how the expand/collapse tests below avoid the same
    // filter-panel contamination.
    String page =
        mockMvc
            .perform(get("/reports/" + saved.reportId()).params(draft))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+>", ">");
    assertThat(page).contains("<th>Cash</th>").doesNotContain("<th>Savings</th>");
  }

  @Test
  void nestedRowsDraftRendersThePayeeBreakdownUnderTheFilteredCategory() throws Exception {
    // The settings strip's "Nested rows" <select> (§3, stage e5) submits rowsNested — proves it
    // reaches the engine end to end: auto (§9.2) expands the one filtered category, and its payee
    // breakdown renders beneath it at depth 1.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 10), "20.00");
    long payee =
        jdbcClient
            .sql("insert into payee (name) values ('ShopAaa') returning payee_id")
            .query(Long.class)
            .single();
    jdbcClient.sql("update transaction set payee_id = :p").param("p", payee).update();
    SavedReport saved =
        reportService.save("Food by shop", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY, Dimension.PAYEE),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(food)))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(spec);

    String page =
        mockMvc
            .perform(get("/reports/" + saved.reportId()).params(draft))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(draft.getFirst("rowsNested")).isEqualTo("PAYEE");
    assertThat(page).contains("padding-left: 20px").contains("ShopAaa");
    // Every other group's hidden fields carry the nested slot, so it survives their own submits.
    assertThat(page.replaceAll("\\s+", " ")).contains("name=\"rowsNested\" value=\"PAYEE\"");
  }

  @Test
  void autoExpandedColumnHeadersMarkTheParentAndItsChildren() throws Exception {
    // Owner finding (stage e): Account on columns filtered to one parent (Cash) auto-expands it
    // (§9.2), and the headers read "Cash, Cash EUR, ..." all alike. The parent — a subtotal over
    // the columns after it — and its children must be told apart.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long cashEur = insertAccount("Wallet", "asset", "EUR", cash);
    long groceries = insertAccount("Groceries", "expense", "EUR", null);
    postSingleCurrency(cashEur, groceries, LocalDate.of(2026, 1, 10), "20.00");
    SavedReport saved =
        reportService.save("Cash activity", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.DATE),
            List.of(Dimension.ACCOUNT),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("asset"),
            List.of(
                new ReportFilter(
                    FilterField.ACCOUNT,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(cash)))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    String page =
        mockMvc
            .perform(
                get("/reports/" + saved.reportId()).params(ReportSpecQueryString.toParams(spec)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+", " ")
            .replace(" >", ">");

    assertThat(page)
        .contains("<th class=\"report__col--parent\">Cash</th>")
        .contains("<th class=\"report__col--child\">Wallet</th>");
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
  void toggleResponseIsThePageFragmentWithNoBodyWrapper() throws Exception {
    // Issue 02: the toggle re-renders the page region, like a settings change, so the settings
    // strip's and actions strip's hidden fields carry the new expansion too. Reporting issue 01's
    // concern still holds: no stray <body> wrapper around the fragment.
    settingsService.setBaseCurrency("EUR");
    long food = insertAccount("Food", "expense", "EUR", null);
    insertAccount("Bakery", "expense", "EUR", food);
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    String fragment =
        mockMvc
            .perform(
                post("/reports/" + saved.reportId() + "/expand")
                    .param("node", String.valueOf(food)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(fragment.strip()).startsWith("<div id=\"report-page\"").doesNotContain("<body");
  }

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
  void dateRowStartsCollapsedThenTogglingRevealsItsDaysAndPersists() throws Exception {
    // Date's own expand-in-place tree (reporting.md §9.1, stage e4b): auto starts it collapsed
    // (§9.2), expanding a month reveals its days, and each day carries its own figure.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 15), "20.00");
    ReportSpec dateRows =
        new ReportSpec(
            List.of(Dimension.DATE),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);
    SavedReport saved = reportService.save("By day", dateRows, Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("Jan 2026")))
        .andExpect(content().string(not(containsString("15 Jan 2026"))));

    String expanded =
        mockMvc
            .perform(post("/reports/" + saved.reportId() + "/expand").param("node", "2026-01"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // Blank days are suppressed (suppressEmptyRows), leaving just the 15th under January.
    assertThat(expanded).contains("15 Jan 2026").contains("padding-left: 20px");
    assertThat(expanded).doesNotContain("14 Jan 2026");

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("15 Jan 2026")));
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
  void multilevelExpandRevealsGrandchildOnceBothAncestorsAreToggled() throws Exception {
    // reporting.md §9.1's own multilevel case: Bakery is itself a child of Food AND has its own
    // child Sourdough — expanding Food then Bakery must reveal Sourdough at depth 2, not stop after
    // one level (the owner's own "multilevel hierarchy" report).
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    long sourdough = insertAccount("Sourdough", "expense", "EUR", bakery);
    postSingleCurrency(cash, sourdough, LocalDate.now(), "5.00");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc.perform(
        post("/reports/" + saved.reportId() + "/expand").param("node", String.valueOf(food)));
    String expanded =
        mockMvc
            .perform(
                post("/reports/" + saved.reportId() + "/expand").param("node", food + "|" + bakery))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(expanded).contains("Sourdough").contains("padding-left: 40px");
  }

  @Test
  void autoExpandsOnlyTheFilteredCategoryNotSiblingTopLevelCategory() {
    // reporting.md §9.2's own worked example names exactly one node — a second, unrelated top-level
    // category with its own child must stay collapsed (the owner's "tags always appear fully
    // expanded" report, reproduced here for Category, end to end against the real repository —
    // ReportEngineTest's own coverage of this mocks the repository out). Asserts on the grid
    // directly rather than scraping the rendered page, whose filter panel lists every category leaf
    // regardless of the grid's own expansion state (see this file's toggle tests above).
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    long fuel = insertAccount("Fuel", "expense", "EUR", null);
    long diesel = insertAccount("Diesel", "expense", "EUR", fuel);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    postSingleCurrency(cash, diesel, LocalDate.now(), "30.00");
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
            false);

    ReportGrid grid = reportEngine.render(filteredToFood);

    assertThat(grid.rows())
        .extracting(AxisNode::key)
        .contains(food + "|" + bakery)
        .doesNotContain(fuel + "|" + diesel);
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

  @Test
  void groupHeaderParentsBlanksAnExpandedParentsOwnFigureButKeepsItsChildsOne() throws Exception {
    // Stage e3 (reporting.md §9.2): a parent row is either a subtotal (default) or a bare group
    // header. Food's own subtotal (20.00, the same figure Bakery alone contributes) must vanish
    // from the rendered page once groupHeaderParents is on and Food is expanded — but Bakery's own
    // figure, right beneath it, must still print.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    ReportSpec base =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("income", "expense"),
            List.of(),
            DateRange.yearToDate(),
            false,
            false,
            false,
            true);
    SavedReport headerOnly = reportService.save("Header-only", base, Renderer.TABLE, false);
    SavedReport subtotal =
        reportService.save(
            "Subtotal",
            new ReportSpec(
                base.rows(),
                base.columns(),
                base.series(),
                base.measures(),
                base.scope(),
                base.filters(),
                base.range(),
                base.rowTotals(),
                base.columnTotals(),
                base.suppressEmptyRows(),
                false),
            Renderer.TABLE,
            false);
    mockMvc.perform(
        post("/reports/" + headerOnly.reportId() + "/expand").param("node", String.valueOf(food)));
    mockMvc.perform(
        post("/reports/" + subtotal.reportId() + "/expand").param("node", String.valueOf(food)));

    String headerOnlyPage =
        mockMvc
            .perform(get("/reports/" + headerOnly.reportId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String subtotalPage =
        mockMvc
            .perform(get("/reports/" + subtotal.reportId()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(headerOnlyPage).contains("Bakery").containsOnlyOnce("20,00");
    assertThat(subtotalPage).contains("Bakery");
    assertThat(occurrences(subtotalPage, "20,00")).isEqualTo(2); // Food's own cell, and Bakery's
  }

  // ── a draft's ephemeral expansion (reporting.md §9.1, issue 02) ─────────────────────────────

  @Test
  void draftOffersToggleThatReGetsThePageRatherThanPersisting() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(content().string(containsString("hx-get=\"/reports/" + saved.reportId() + "?")))
        .andExpect(content().string(not(containsString("report__toggle--static"))));

    draft.add(RowToggle.EXPANDED, String.valueOf(food));
    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(content().string(containsString("padding-left: 20px")))
        .andExpect(content().string(containsString("Unsaved changes")));

    // Ephemeral: the saved Report's own remembered state is untouched.
    assertThat(reportService.find(saved.reportId()).orElseThrow().expandedNodeKeys()).isNull();
  }

  @Test
  void draftsExpansionRidesAlongInEverySettingsFormSoItSurvivesTheNextChange() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", "EUR", null);
    long food = insertAccount("Food", "expense", "EUR", null);
    long bakery = insertAccount("Bakery", "expense", "EUR", food);
    postSingleCurrency(cash, bakery, LocalDate.now(), "20.00");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    draft.add(RowToggle.EXPANDED, String.valueOf(food));

    String page =
        mockMvc
            .perform(get("/reports/" + saved.reportId()).params(draft))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+", " ");

    String hiddenExpansion = "<input type=\"hidden\" name=\"expanded\" value=\"" + food + "\" />";
    // Rows & columns, Measures, Scope (x2), Filters, Date range, Display, Renderer, and both
    // Save forms all resubmit it.
    assertThat(occurrences(page, hiddenExpansion)).isGreaterThanOrEqualTo(8);

    // The next change (Display: row totals flipped) resubmits it, and Food is still expanded.
    draft.set("rowTotals", String.valueOf(!Presets.categoryMonthMatrix().rowTotals()));
    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(content().string(containsString("padding-left: 20px")));
  }

  @Test
  void savingDraftKeepsItsExpansionAsTheReportsRememberedState() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    draft.add(RowToggle.EXPANDED, "7");
    draft.add("name", "My matrix");
    draft.add("renderer", "TABLE");
    draft.add("trendLine", "false");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/save").params(draft))
        .andExpect(status().is3xxRedirection());

    assertThat(reportService.find(saved.reportId()).orElseThrow().expandedNodeKeys())
        .containsExactly("7");
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int index = haystack.indexOf(needle);
        index >= 0;
        index = haystack.indexOf(needle, index + 1)) {
      count++;
    }
    return count;
  }
}
