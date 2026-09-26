package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): a Report's CSV export ({@link ReportExportController},
 * reporting.md §13) — the download and the editor's buttons that ask for it. The raw grid's leaves
 * are {@code RawReportSqlLogicTest}'s job and the CSV's shape {@code ReportCsvTest}'s; this proves
 * the two forms reach the browser as files.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportExportControllerIntegrationTest {

  private static final String EXPORT_PATH = "/reports/export";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private long insertAccount(String name, String type, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) values (:n, :t, 'EUR', :p)"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void spend(long payer, long category, LocalDate date, String amount) {
    long txn =
        jdbcClient
            .sql("insert into transaction (date) values (:d) returning transaction_id")
            .param("d", date)
            .query(Long.class)
            .single();
    insertPosting(txn, payer, new BigDecimal(amount).negate());
    insertPosting(txn, category, new BigDecimal(amount));
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
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        true,
        true,
        true);
  }

  private void seedFoodWithLunch() {
    settingsService.setBaseCurrency("EUR");
    long cash = insertAccount("Cash", "asset", null);
    long food = insertAccount("Food", "expense", null);
    long lunch = insertAccount("Lunch", "expense", food);
    spend(cash, lunch, LocalDate.of(2026, 1, 15), "20.00");
    spend(cash, lunch, LocalDate.of(2026, 1, 20), "30.50");
  }

  @Test
  void shownFormDownloadsTheGridWithItsTotals() throws Exception {
    seedFoodWithLunch();

    String csv =
        mockMvc
            .perform(
                get(EXPORT_PATH)
                    .params(ReportSpecQueryString.toParams(januaryByCategory()))
                    .param("form", "shown")
                    .param("name", "Monthly spending"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=\"monthly-spending.csv\"; filename*=UTF-8''"
                            + "monthly-spending.csv"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(csv)
        .isEqualTo(
            "﻿Category,2026-01,Total\r\n" + "Food,50.50,50.50\r\n" + "Total,50.50,50.50\r\n");
  }

  @Test
  void rawDownloadsEveryLeafByItsPathWithoutTotals() throws Exception {
    seedFoodWithLunch();

    String csv =
        mockMvc
            .perform(
                get(EXPORT_PATH)
                    .params(ReportSpecQueryString.toParams(januaryByCategory()))
                    .param("form", "raw"))
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=\"report-raw.csv\"; filename*=UTF-8''report-raw.csv"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(csv).isEqualTo("﻿Category,2026-01\r\n" + "Food:Lunch,50.50\r\n");
  }

  @Test
  void unknownFormIsBadRequest() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(
            get(EXPORT_PATH)
                .params(ReportSpecQueryString.toParams(januaryByCategory()))
                .param("form", "pdf"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theEditorOffersBothExportsOfWhatItShows() throws Exception {
    seedFoodWithLunch();

    String page =
        mockMvc
            .perform(get("/reports/preset/category-month-matrix"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll("\\s+", " ");

    String exportForm =
        page.substring(
            page.indexOf("action=\"/reports/export\""),
            page.indexOf("</form>", page.indexOf("action=\"/reports/export\"")));
    assertThat(exportForm)
        .contains("name=\"rows\" value=\"CATEGORY\"")
        .contains("name=\"form\" value=\"shown\"")
        .contains("name=\"form\" value=\"raw\"");
  }
}
