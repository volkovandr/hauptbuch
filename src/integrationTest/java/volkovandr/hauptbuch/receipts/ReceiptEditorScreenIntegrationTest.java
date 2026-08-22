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

  /** A response whose tags cell echoes a tag path the operator has since created. */
  private static final String TAGGED_RESPONSE =
      """
      merchant:
        name: Total Tankstelle
      transaction:
        date: 2026-07-21
        totalAmount: 42.14
        currency: EUR
      items[1]{name,totalPrice,category,tags}:
        Diesel Fuel,42.14,Fuel,"Trips:France-2026"
      """;

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
  void undetectedAccountLeavesThePlaceholderSelected() throws Exception {
    // 'Cash' sorts first among the accounts, which is exactly what a browser used to pre-select
    // when detection found nothing — submitting a wrong account that Confirm then waved through.
    account("Cash", "asset", "EUR");
    account("Girocard", "asset", "EUR");
    long id = undetectedReceipt("5.00", "EUR");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("pick an account")))
        .andExpect(content().string(Matchers.containsString("value=\"\" selected=\"selected\"")));
  }

  @Test
  void saveKeepsTheDraftWithNoAccountPicked() throws Exception {
    // Save is the lenient rung: an undetected account must survive a round-trip rather than being
    // rejected or silently filled in. Confirm is where the missing account is a hard block.
    account("Cash", "asset", "EUR");
    long id = undetectedReceipt("5.00", "EUR");

    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/save")
                .param("date", "2026-07-21")
                .param("accountId", "")
                .param("currencyCode", "EUR")
                .param("total", "5,00")
                .param("lineDescription", "Snack")
                .param("lineAmount", "5,00"))
        .andExpect(status().isOk());

    Long saved =
        jdbcClient
            .sql("select account_id from receipt where receipt_id = :id")
            .param("id", id)
            .query(Long.class)
            .optional()
            .orElse(null);
    assertThat(saved).isNull();
    assertThat(linesOf(id)).hasSize(1);
  }

  @Test
  void detectedAccountLeavesThePlaceholderUnselected() throws Exception {
    long pay = account("Girocard", "asset", "EUR");
    long id = processedReceipt(pay, "5.00", "EUR");

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(Matchers.not(Matchers.containsString("value=\"\" selected=\"selected\""))));
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

  // ── Re-seed from the stored AI response (issue tracker receipt-processing/19) ──

  /**
   * The reported case, end to end: a receipt whose response named a tag the taxonomy did not have
   * at analysis time was seeded tag-less and silently so. Once the tag exists, re-seeding the very
   * same stored text — no API call — attaches it, and the stale lines it replaces are gone.
   */
  @Test
  void reSeedAttachesTagThatDidNotExistAtAnalysisTime() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long fuel = account("Fuel", "expense", "EUR");
    final long trip = tag("France-2026", tag("Trips", null));
    long id = processedReceipt(pay, "42.14", "EUR");
    // What the first, tag-less seeding left behind: the response was good, the tag was not there.
    line(id, "Stale line", "42.14", fuel, null, null);

    mockMvc
        .perform(post("/receipts/" + id + "/reparse").param("rawText", TAGGED_RESPONSE))
        .andExpect(status().is3xxRedirection());

    assertThat(state(id)).isEqualTo("processed");
    assertThat(linesOf(id)).extracting(ReceiptLineRow::description).containsExactly("Diesel Fuel");
    assertThat(lineTagIdsOf(id)).containsExactly(trip);
  }

  /** The stored response is the only copy — an empty submit must not overwrite it. */
  @Test
  void reSeedWithBlankTextLeavesTheStoredResponseAndLinesAlone() throws Exception {
    long pay = account("Cash", "asset", "EUR");
    long id = processedReceipt(pay, "42.14", "EUR");
    storeRaw(id, TAGGED_RESPONSE);
    line(id, "Reviewed line", "42.14", null, null, null);

    mockMvc
        .perform(post("/receipts/" + id + "/reparse").param("rawText", "   "))
        .andExpect(status().is3xxRedirection());

    assertThat(state(id)).isEqualTo("processed");
    assertThat(rawOf(id)).isEqualTo(TAGGED_RESPONSE);
    assertThat(linesOf(id))
        .extracting(ReceiptLineRow::description)
        .containsExactly("Reviewed line");
  }

  private long tag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void storeRaw(long receiptId, String raw) {
    jdbcClient
        .sql("update receipt set parse_raw = :raw where receipt_id = :id")
        .param("raw", raw)
        .param("id", receiptId)
        .update();
  }

  private String rawOf(long receiptId) {
    return jdbcClient
        .sql("select parse_raw from receipt where receipt_id = :id")
        .param("id", receiptId)
        .query(String.class)
        .single();
  }

  private String state(long receiptId) {
    return jdbcClient
        .sql("select state from receipt where receipt_id = :id")
        .param("id", receiptId)
        .query(String.class)
        .single();
  }

  private List<Long> lineTagIdsOf(long receiptId) {
    return jdbcClient
        .sql(
            """
            select t.tag_id from receipt_line_tag t
            join receipt_line l on l.receipt_line_id = t.receipt_line_id
            where l.receipt_id = :id
            order by t.tag_id
            """)
        .param("id", receiptId)
        .query(Long.class)
        .list();
  }

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

  /** A processed receipt whose paying account detection found nothing — the operator must pick. */
  private long undetectedReceipt(String total, String currency) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                 total_amount, currency_code)
            values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                    :total, :currency)
            returning receipt_id
            """)
        .param("total", new BigDecimal(total))
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
