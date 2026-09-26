package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * Integration tier (CLAUDE.md §6): a Report figure's drill-down page ({@link
 * ReportDrillDownController}, reporting.md §12) and the way a table offers it. The list's posting
 * set and running column are {@code ReportDrillDownSqlLogicTest}'s job; this proves the page shows
 * them as register rows handing off to the register, and that each figure on a Report's table
 * submits to its own drill-down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportDrillDownControllerIntegrationTest {

  private static final String CELL_PATH = "/reports/cell";
  private static final Measure BASE_NET = Measure.turnover(PresentationCurrency.BASE, Leg.NET);

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR') "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  /** A two-leg purchase on {@code date}; returns its transaction id. */
  private long spend(long payer, long category, LocalDate date, String amount) {
    long txn =
        jdbcClient
            .sql("insert into transaction (date) values (:d) returning transaction_id")
            .param("d", date)
            .query(Long.class)
            .single();
    insertPosting(txn, payer, new BigDecimal(amount).negate());
    insertPosting(txn, category, new BigDecimal(amount));
    return txn;
  }

  private void insertPosting(long txn, long account, BigDecimal amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txn)
        .param("a", account)
        .param("amt", amount)
        .update();
  }

  private static ReportSpec januaryByCategory() {
    return new ReportSpec(
        List.of(Dimension.CATEGORY),
        List.of(Dimension.DATE),
        List.of(),
        List.of(BASE_NET),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        true,
        true,
        true);
  }

  @Test
  void figureListsItsPostingsAsRegisterRowsWithTheRunningColumn() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset");
    long card = insertAccount("Card", "liability");
    long food = insertAccount("Food", "expense");
    long lunch = spend(cash, food, LocalDate.of(2026, 1, 15), "20.00");
    long dinner = spend(card, food, LocalDate.of(2026, 1, 20), "30.50");

    mockMvc
        .perform(
            get(CELL_PATH)
                .params(ReportSpecQueryString.toParams(januaryByCategory()))
                .param("cell", "0/" + food + "/2026-01"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Food"),
                        containsString("class=\"register__row\""),
                        // each posting's own amount, then the running column ending on the figure
                        containsString("30,50"),
                        containsString(">50,50<"),
                        containsString("/register?selected=" + lunch),
                        containsString("/register?selected=" + dinner),
                        // the list page has no entry dock, so no row loads into one
                        not(containsString("hx-get=\"/register/edit/")))));
  }

  @Test
  void rowTotalListsTheWholeRow() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset");
    long food = insertAccount("Food", "expense");
    spend(cash, food, LocalDate.of(2026, 1, 15), "20.00");
    spend(cash, food, LocalDate.of(2026, 1, 20), "5.00");

    mockMvc
        .perform(
            get(CELL_PATH)
                .params(ReportSpecQueryString.toParams(januaryByCategory()))
                .param("cell", "0/" + food + "/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">25,00<")));
  }

  @Test
  void malformedCellIsBadRequest() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(
            get(CELL_PATH)
                .params(ReportSpecQueryString.toParams(januaryByCategory()))
                .param("cell", "not-a-cell"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void eachFigureOnReportTablesSubmitsToItsDrillDown() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset");
    long food = insertAccount("Food", "expense");
    spend(cash, food, LocalDate.now(), "42.50");
    String thisMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

    String page =
        mockMvc
            .perform(get("/reports/preset/category-month-matrix"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+", " ");

    String drillForm =
        page.substring(
            page.indexOf("class=\"report__drill-form\""),
            page.indexOf("</form>", page.indexOf("class=\"report__drill-form\"")));
    assertThat(drillForm)
        .contains("action=\"/reports/cell\"")
        .contains("name=\"cell\" value=\"0/" + food + "/" + thisMonth + "\"")
        // the row total, the column total and the grand total open their own lists too
        .contains("name=\"cell\" value=\"0/" + food + "/\"")
        .contains("name=\"cell\" value=\"0//" + thisMonth + "\"")
        .contains("name=\"cell\" value=\"0//\"")
        // the spec travels once, as the form's own hidden fields
        .containsOnlyOnce("name=\"rows\" value=\"CATEGORY\"");
  }

  @Test
  void theLayoutEditorsPreviewOffersNoDrillDown() throws Exception {
    // The editor wraps every Frame in its own form, and forms cannot nest.
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset");
    long food = insertAccount("Food", "expense");
    spend(cash, food, LocalDate.now(), "42.50");

    mockMvc
        .perform(
            post("/reports/layout/resize")
                .param("rowCount", "1")
                .param("columnCount", "1")
                .param("frame-0-0", "preset:category-month-matrix"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(allOf(containsString("42,50"), not(containsString(CELL_PATH)))));
  }
}
