package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
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

/**
 * Integration tier (§1.5): the stage-9f post-process editor driven through its controller — the
 * editable surface, the add/remove/redistribute round-trips, the ghost hint, the currency-mismatch
 * warning, and the Save delete-and-reinsert. The shared line-editor fragment reuse is asserted via
 * the {@code data-split-line} markers the register and receipts both emit; the JS leaf stays
 * untested (standing rule).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptEditorScreenIntegrationTest {

  private static final Path STORAGE_ROOT = tempRoot();

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @Test
  void rendersTheEditorWithSeededCategoryLine() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long fuel = account("Fuel", "expense", "EUR");
    long id = processedReceipt(pay, "42.14", "EUR");
    line(id, "Diesel", "42.14", fuel, null, null);

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("id=\"receipt-editor\"")))
        .andExpect(content().string(Matchers.containsString("data-split-line")))
        .andExpect(content().string(Matchers.containsString("Diesel")))
        .andExpect(content().string(Matchers.containsString("Fuel")))
        // The header currency picker must render its options (else it is an empty <select required>
        // that blocks Save) — the seeded EUR currency is one.
        .andExpect(content().string(Matchers.containsString("value=\"EUR\"")))
        // The first line's tag resolve must read its OWN tagText slot: the receipts editor has no
        // header tag input, so line 0 is index 0 (a header-1-based index reads an empty slot and
        // silently drops the chip).
        .andExpect(
            content()
                .string(
                    Matchers.containsString(
                        "&quot;fieldName&quot;:&quot;lineTag0&quot;,&quot;index&quot;:0")));
  }

  @Test
  void prefillsThePayeeWithMerchantNameCityAndCountry() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = receiptWithMerchant(pay, "Rewe", "Dortmund", "Germany");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Rewe - Dortmund - Germany")));
  }

  @Test
  void showsTheGhostHintOnAnUnresolvedLine() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = processedReceipt(pay, "5.00", "EUR");
    line(id, "Mystery", "5.00", null, null, "Snacks - Sweets");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("AI said: Snacks - Sweets")));
  }

  @Test
  void warnsWhenTheCurrencyDiffersFromTheAccount() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = processedReceipt(pay, "5.00", "USD");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("differs from the paying account")));
  }

  @Test
  void addLineReturnsAnExtraRow() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = processedReceipt(pay, "20.00", "EUR");

    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/add-line")
                .param("total", "20,00")
                .param("accountId", String.valueOf(pay))
                .param("lineAmount", "10,00")
                .param("lineDescription", "One"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(Matchers.stringContainsInOrder("data-split-line", "data-split-line")));
  }

  @Test
  void removeLineDropsRow() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = processedReceipt(pay, "20.00", "EUR");

    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/remove-line")
                .param("index", "0")
                .param("total", "20,00")
                .param("accountId", String.valueOf(pay))
                .param("lineAmount", "10,00", "10,00")
                .param("lineDescription", "One", "Two"))
        .andExpect(status().isOk())
        .andExpect(content().string(occursExactly("class=\"split-line\"", 1)));
  }

  @Test
  void redistributeSpreadsTheLineOverTheOthersAndDropsIt() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long fuel = account("Fuel", "expense", "EUR");
    long id = processedReceipt(pay, "33.00", "EUR");

    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/redistribute")
                .param("index", "2")
                .param("total", "33,00")
                .param("accountId", String.valueOf(pay))
                .param("lineAmount", "10,00", "20,00", "3,00")
                .param(
                    "lineCategoryId",
                    String.valueOf(fuel),
                    String.valueOf(fuel),
                    String.valueOf(fuel))
                .param("lineCategoryType", "expense", "expense", "expense")
                .param("lineDescription", "A", "B", "Tax"))
        .andExpect(status().isOk())
        .andExpect(content().string(occursExactly("class=\"split-line\"", 2)))
        .andExpect(content().string(Matchers.containsString("11,00")))
        .andExpect(content().string(Matchers.containsString("22,00")));
  }

  @Test
  void saveDeleteAndReinsertsLinesAndUpdatesTheHeader() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long fuel = account("Fuel", "expense", "EUR");
    long id = processedReceipt(pay, null, null);
    line(id, "Old", "1.00", null, null, null); // replaced wholesale by Save

    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/save")
                .param("date", "2026-07-21")
                .param("payeeText", "")
                .param("accountId", String.valueOf(pay))
                .param("currencyCode", "EUR")
                .param("total", "43,14")
                .param("lineDescription", "Diesel", "Snack")
                .param("categoryText", "Fuel", "")
                .param("lineCategoryId", String.valueOf(fuel), "")
                .param("lineCategoryType", "expense", "")
                .param("lineAmount", "42,14", "1,00"))
        .andExpect(status().isOk());

    List<ReceiptLineRow> lines = linesOf(id);
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).description()).isEqualTo("Diesel");
    assertThat(lines.get(0).amount()).isEqualByComparingTo("42.14");
    assertThat(lines.get(0).accountId()).isEqualTo(fuel);
    assertThat(lines.get(1).description()).isEqualTo("Snack");
    assertThat(lines.get(1).accountId()).isNull();

    assertThat(header(id, "total_amount", BigDecimal.class)).isEqualByComparingTo("43.14");
    assertThat(header(id, "account_id", Long.class)).isEqualTo(pay);
    assertThat(header(id, "state", String.class)).isEqualTo("processed");
  }

  // ── seeding + helpers ───────────────────────────────────────────────────────

  private long account(String name, String type, String currency) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, :c)"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .query(Long.class)
        .single();
  }

  private long processedReceipt(long accountId, String total, String currency) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                 account_id, total_amount, currency_code)
            values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                    :accountId, :total, :currency)
            returning receipt_id
            """)
        .param("accountId", accountId)
        .param("total", total == null ? null : new BigDecimal(total))
        .param("currency", currency)
        .query(Long.class)
        .single();
  }

  private long receiptWithMerchant(long accountId, String merchant, String city, String country) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                 account_id, currency_code, merchant_text, merchant_city,
                                 merchant_country)
            values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                    :accountId, 'EUR', :merchant, :city, :country)
            returning receipt_id
            """)
        .param("accountId", accountId)
        .param("merchant", merchant)
        .param("city", city)
        .param("country", country)
        .query(Long.class)
        .single();
  }

  private void line(
      long receiptId,
      String description,
      String amount,
      Long accountId,
      Long personId,
      String aiTargetText) {
    jdbcClient
        .sql(
            """
            insert into receipt_line (receipt_id, description, amount, account_id, person_id,
                                     sort_order, ai_target_text)
            values (:id, :d, :a, :account, :person, 0, :ai)
            """)
        .param("id", receiptId)
        .param("d", description)
        .param("a", new BigDecimal(amount))
        .param("account", accountId)
        .param("person", personId)
        .param("ai", aiTargetText)
        .update();
  }

  private List<ReceiptLineRow> linesOf(long receiptId) {
    return jdbcClient
        .sql(
            "select description, amount, account_id from receipt_line where receipt_id = :id"
                + " order by sort_order, receipt_line_id")
        .param("id", receiptId)
        .query(ReceiptLineRow.class)
        .list();
  }

  private <T> T header(long receiptId, String column, Class<T> type) {
    return jdbcClient
        .sql("select " + column + " from receipt where receipt_id = :id")
        .param("id", receiptId)
        .query(type)
        .single();
  }

  /** A Hamcrest matcher asserting {@code needle} occurs exactly {@code times} in the content. */
  private static org.hamcrest.Matcher<String> occursExactly(String needle, int times) {
    return new org.hamcrest.CustomMatcher<>("'" + needle + "' exactly " + times + " times") {
      @Override
      public boolean matches(Object actual) {
        if (!(actual instanceof String haystack)) {
          return false;
        }
        int count = 0;
        for (int from = haystack.indexOf(needle);
            from >= 0;
            from = haystack.indexOf(needle, from + needle.length())) {
          count++;
        }
        return count == times;
      }
    };
  }

  private record ReceiptLineRow(String description, BigDecimal amount, Long accountId) {}

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-editor-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
