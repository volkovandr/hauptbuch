package volkovandr.hauptbuch.analytics;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the landing-page tracking-stats line (CONTEXT.md "Tracking stats")
 * driven through {@link TrackingStatsController} and the landing page against real Postgres — the
 * empty-book body, the no-receipt-clause variant, the full line, and the lazy-load container's
 * presence gate on {@code /}.
 *
 * <p>Transactions and analyzed receipts are seeded by raw JDBC (this is a read surface; the entry
 * engine and the receipt pipeline own those writes). Each test rolls back, including the write-once
 * base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TrackingStatsScreenIntegrationTest {

  private static final String PATH = "/overview/tracking-stats";
  private static final Path STORAGE_ROOT = tempRoot();

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-tracking-stats-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void seedTransaction(LocalDate date) {
    jdbcClient.sql("insert into transaction (date) values (:d)").param("d", date).update();
  }

  private void seedAnalyzedReceipt() {
    jdbcClient
        .sql(
            "insert into receipt (state, source, original_path, parse_raw)"
                + " values ('processed', 'pc', 'originals/2026/07/a.jpg', '{\"ok\":true}')")
        .update();
  }

  @Test
  void rendersNothingOnaBookWithNoTransactions() throws Exception {
    seedAnalyzedReceipt(); // a receipt without any transaction still yields no line

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Keeping track"))));
  }

  @Test
  void dropsTheReceiptClauseWhenNoReceiptHasBeenAnalyzed() throws Exception {
    seedTransaction(LocalDate.now().minusYears(2).minusMonths(8));
    seedTransaction(LocalDate.now().minusMonths(1));

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Keeping track of your finances for"),
                        containsString("2 years and 8 months"),
                        containsString("2 transactions"),
                        not(containsString("analyzed")))));
  }

  @Test
  void rendersTheFullLineWithTheAnalyzedReceiptClause() throws Exception {
    seedTransaction(LocalDate.now().minusMonths(3));
    seedAnalyzedReceipt();
    seedAnalyzedReceipt();

    mockMvc
        .perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("1 transaction"),
                        containsString("2 receipts analyzed ("),
                        containsString("B)"))));
  }

  @Test
  void landingMountsTheLazyLoadContainerOnceTheBaseCurrencyIsSet() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/overview/tracking-stats")))
        .andExpect(content().string(containsString("hx-trigger=\"load\"")));
  }

  @Test
  void landingOmitsTheContainerBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("/overview/tracking-stats"))));
  }
}
