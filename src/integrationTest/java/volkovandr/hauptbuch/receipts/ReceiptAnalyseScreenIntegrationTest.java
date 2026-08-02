package volkovandr.hauptbuch.receipts;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (§1.5): the stage-9e analyse UI driven through its controller — the Analyse
 * action, the greyed processing view + status poll, and Retry. The background worker (its network
 * call) is stubbed here ({@link ReceiptAnalyser} mocked) so the controller contract is asserted
 * deterministically; the worker's own orchestration is unit-tested.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptAnalyseScreenIntegrationTest {

  private static final Path STORAGE_ROOT = tempRoot();

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  @MockitoBean ReceiptAnalyser receiptAnalyser;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @Test
  void analyseStartsTheWorkerAndRedirects() throws Exception {
    when(receiptAnalyser.start(anyLong())).thenReturn(true);
    long id = seed("pre_processed");

    mockMvc
        .perform(post("/receipts/" + id + "/analyse"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/receipts/" + id + "*"));

    verify(receiptAnalyser).start(id);
  }

  @Test
  void processingScreenShowsThePoll() throws Exception {
    long id = seed("processing");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-receipt-status")))
        .andExpect(content().string(Matchers.containsString("Analysing")));
  }

  @Test
  void statusWhileProcessingReturnsThePollFragment() throws Exception {
    long id = seed("processing");

    mockMvc
        .perform(get("/receipts/" + id + "/status"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("hx-trigger")))
        .andExpect(header().doesNotExist("HX-Refresh"));
  }

  @Test
  void statusWhenDoneAsksHtmxToRefresh() throws Exception {
    long id = seed("processed");

    mockMvc
        .perform(get("/receipts/" + id + "/status"))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Refresh", "true"));
  }

  @Test
  void processedScreenShowsSeededLinesAndTelemetry() throws Exception {
    long id = seed("processed");
    jdbcClient
        .sql("update receipt set tokens_in = 1200, tokens_out = 300 where receipt_id = :id")
        .param("id", id)
        .update();
    jdbcClient
        .sql(
            """
            insert into receipt_line (receipt_id, description, amount, sort_order)
            values (:id, 'Diesel Fuel', 42.14, 0)
            """)
        .param("id", id)
        .update();

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Diesel Fuel")))
        .andExpect(content().string(Matchers.containsString("tokens")));
  }

  @Test
  void processedScreenShowsMerchantLineAndGreenSumWhenItemsMatch() throws Exception {
    long id = seed("processed");
    jdbcClient
        .sql(
            """
            update receipt set merchant_text = 'Total Tankstelle', merchant_city = 'Berlin',
              merchant_country = 'Germany', total_amount = 42.14, currency_code = 'EUR'
            where receipt_id = :id
            """)
        .param("id", id)
        .update();
    insertLine(id, "Diesel Fuel", "42.14");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Total Tankstelle - Berlin - Germany")))
        .andExpect(content().string(Matchers.containsString("receipt-summatch--ok")))
        // The line table now carries a Category column (owner feedback 2026-08-02).
        .andExpect(content().string(Matchers.containsString("Category")));
  }

  @Test
  void processedScreenShowsYellowWarningWhenItemsDoNotMatch() throws Exception {
    long id = seed("processed");
    jdbcClient
        .sql(
            "update receipt set total_amount = 42.14, currency_code = 'EUR' where receipt_id = :id")
        .param("id", id)
        .update();
    insertLine(id, "Diesel Fuel", "40.00");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("receipt-summatch--warn")));
  }

  private void insertLine(long receiptId, String description, String amount) {
    jdbcClient
        .sql(
            """
            insert into receipt_line (receipt_id, description, amount, sort_order)
            values (:id, :description, :amount, 0)
            """)
        .param("id", receiptId)
        .param("description", description)
        .param("amount", new java.math.BigDecimal(amount))
        .update();
  }

  @Test
  void retryReturnsFailedReceiptToPreProcessed() throws Exception {
    long id = seed("failed");

    mockMvc.perform(post("/receipts/" + id + "/retry")).andExpect(status().is3xxRedirection());

    String state =
        jdbcClient
            .sql("select state from receipt where receipt_id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    org.assertj.core.api.Assertions.assertThat(state).isEqualTo("pre_processed");
  }

  @Test
  void failedScreenRevealsTheReturnedTextForEditing() throws Exception {
    long id = seed("failed");
    jdbcClient
        .sql(
            "update receipt set parse_error = 'Could not decode', parse_raw = :raw"
                + " where receipt_id = :id")
        .param("raw", "merchant: Total,Tankstelle")
        .param("id", id)
        .update();

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Details")))
        .andExpect(content().string(Matchers.containsString("merchant: Total,Tankstelle")))
        .andExpect(content().string(Matchers.containsString("/reparse")));
  }

  @Test
  void reparseCallsTheWorkerAndRedirects() throws Exception {
    long id = seed("failed");

    mockMvc
        .perform(post("/receipts/" + id + "/reparse").param("rawText", "fixed \"good, toon\""))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/receipts/" + id + "*"));

    verify(receiptAnalyser).reparse(id, "fixed \"good, toon\"");
  }

  private long seed(String state) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, edited_path, edit_recipe)
            values (:state, 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}')
            returning receipt_id
            """)
        .param("state", state)
        .query(Long.class)
        .single();
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-analyse-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
