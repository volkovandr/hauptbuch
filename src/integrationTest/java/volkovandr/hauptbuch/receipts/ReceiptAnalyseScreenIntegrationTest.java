package volkovandr.hauptbuch.receipts;

import static org.mockito.ArgumentMatchers.anyBoolean;
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
    when(receiptAnalyser.start(anyLong(), anyBoolean())).thenReturn(true);
    long id = seed("pre_processed");

    mockMvc
        .perform(post("/receipts/" + id + "/analyse"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/receipts/" + id + "*"));

    verify(receiptAnalyser).start(id, false);
  }

  /** The 9h second button: the same POST, carrying the cache-the-prefix choice. */
  @Test
  void analyseCachedPassesTheCacheFlagToTheWorker() throws Exception {
    when(receiptAnalyser.start(anyLong(), anyBoolean())).thenReturn(true);
    long id = seed("pre_processed");

    mockMvc
        .perform(post("/receipts/" + id + "/analyse").param("cached", "true"))
        .andExpect(status().is3xxRedirection());

    verify(receiptAnalyser).start(id, true);
  }

  /** Both Analyse buttons render on the pre-process view (9h), submitting the one form. */
  @Test
  void preProcessedViewOffersBothAnalyseButtons() throws Exception {
    long id = seed("pre_processed");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("name=\"cached\" value=\"false\"")))
        .andExpect(content().string(Matchers.containsString("value=\"true\"")))
        .andExpect(content().string(Matchers.containsString("Analyse (cached)")));
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
  void processedScreenRendersThePostProcessEditor() throws Exception {
    // The processed view is now the 9f post-process editor: the header prefills the payee from the
    // parsed merchant and the total, the line seeds its item, and the live remaining readout shows.
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
        .andExpect(content().string(Matchers.containsString("id=\"receipt-editor\"")))
        .andExpect(content().string(Matchers.containsString("Total Tankstelle")))
        .andExpect(content().string(Matchers.containsString("42,14")))
        .andExpect(content().string(Matchers.containsString("Diesel Fuel")))
        .andExpect(content().string(Matchers.containsString("data-split-remaining")));
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

  /**
   * Issue tracker receipt-processing/19: the failed view's edit-and-re-parse affordance, extended
   * to a processed receipt so its stored response can be re-seeded against today's taxonomy.
   */
  @Test
  void processedScreenRevealsTheStoredResponseForReSeeding() throws Exception {
    long id = seed("processed");
    storeRaw(id, "tags: Trips:France-2026");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("edit and re-seed")))
        .andExpect(content().string(Matchers.containsString("tags: Trips:France-2026")))
        .andExpect(content().string(Matchers.containsString("/reparse")));
  }

  /** A committed receipt's lines back a real transaction — it gets no re-seed panel at all. */
  @Test
  void committedScreenOffersNoReSeedPanel() throws Exception {
    long id = seed("committed");
    storeRaw(id, "tags: Trips:France-2026");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.not(Matchers.containsString("/reparse"))));
  }

  @Test
  void reparseFromProcessedCallsTheWorkerAndRedirects() throws Exception {
    long id = seed("processed");

    mockMvc
        .perform(post("/receipts/" + id + "/reparse").param("rawText", "tags: Trips:France-2026"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/receipts/" + id + "*"));

    verify(receiptAnalyser).reparse(id, "tags: Trips:France-2026");
  }

  private void storeRaw(long receiptId, String raw) {
    jdbcClient
        .sql("update receipt set parse_raw = :raw where receipt_id = :id")
        .param("raw", raw)
        .param("id", receiptId)
        .update();
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
