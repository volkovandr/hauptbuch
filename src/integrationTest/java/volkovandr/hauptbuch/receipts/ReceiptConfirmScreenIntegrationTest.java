package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (§1.5): the money-critical receipt flow driven through its controllers against
 * real Postgres (plan §9g) — Confirm books a balanced transaction, the gate's hard blocks refuse
 * without writing, Reopen returns the receipt to the editor with Re-enter offered, Re-entering
 * voids its predecessor and books anew, and the committed 5-way delete keeps the audit link either
 * way. This replaces the retired Playwright smoke (plan §14, owner decision 2026-07-05).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptConfirmScreenIntegrationTest {

  private static final Path STORAGE_ROOT = ReceiptImages.tempStorageRoot();
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String DAY = "2026-08-03";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  // ── The money-critical flow, end to end ─────────────────────────────────────

  @Test
  void captureThroughConfirmBooksTheTransaction() throws Exception {
    // Capture from the PC (source = pc), then pre-process: the real multipart endpoints, the real
    // storage layer, the real state transitions.
    mockMvc
        .perform(multipart("/receipts/upload").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection());
    long id = lastReceiptId();
    mockMvc
        .perform(
            multipart("/receipts/" + id + "/pre-process")
                .file(ReceiptImages.editedJpegPart())
                .param("editRecipe", "{}")
                .param("aiNote", "this is fuel"))
        .andExpect(status().is3xxRedirection());
    assertThat(header(id, "state", String.class)).isEqualTo("pre_processed");

    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");

    // The analyse step is the one hop no suite may take (it is a billed API call — plan §9e), so
    // its result is stood in for: the receipt lands `processed` with the header the parser fills.
    seedParseResult(id, cash, "42.14");

    // Post-process: the operator reviews and saves the draft through the real editor endpoint…
    mockMvc
        .perform(
            post("/receipts/" + id + "/lines/save")
                .param("date", DAY)
                .param("accountId", String.valueOf(cash))
                .param("currencyCode", EUR)
                .param("total", "42,14")
                .param("note", "Full tank")
                .param("lineDescription", "Diesel")
                .param("categoryText", "Fuel")
                .param("lineCategoryId", String.valueOf(fuel))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "42,14"))
        .andExpect(status().isOk());

    // …and confirms it.
    mockMvc
        .perform(confirm(id, cash, "42,14", fuel, "42,14").param("note", "Full tank"))
        .andExpect(status().isOk());

    long transactionId = header(id, "transaction_id", Long.class);
    assertThat(header(id, "state", String.class)).isEqualTo("committed");
    assertThat(legsOf(transactionId))
        .containsExactlyInAnyOrder(leg(cash, "-42.14"), leg(fuel, "42.14"));
  }

  // ── Confirm ─────────────────────────────────────────────────────────────────

  @Test
  void confirmBooksBalancedPostingsAndCommitsTheReceipt() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);

    mockMvc
        .perform(confirm(id, cash, "42,14", fuel, "42,14").param("note", "Full tank"))
        .andExpect(status().isOk())
        // Confirm never navigates: the same pane comes back, now read-only (plan §9g).
        .andExpect(content().string(containsString("id=\"receipt-pane\"")))
        .andExpect(content().string(containsString("disabled=\"disabled\"")))
        .andExpect(content().string(containsString("Reopen")))
        .andExpect(content().string(containsString("Edit transaction")))
        // …and the chrome follows out-of-band, so the state badge and the Delete rung keep up.
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("/delete-committed-confirm")));

    Long transactionId = header(id, "transaction_id", Long.class);
    assertThat(header(id, "state", String.class)).isEqualTo("committed");
    assertThat(transactionId).isNotNull();
    assertThat(noteOf(transactionId)).isEqualTo("Full tank");
    assertThat(legsOf(transactionId))
        .containsExactlyInAnyOrder(leg(cash, "-42.14"), leg(fuel, "42.14"));
  }

  @Test
  void confirmedReceiptKeepsTheReviewedDraftAsTheAuditLink() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);

    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());

    // parse_raw → receipt_line → postings: the middle link survives the commit (data-model §13.2).
    assertThat(lineCount(id)).isEqualTo(1);
  }

  @Test
  void confirmRefusesCrossCurrencyReceiptAsNotImplemented() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", CHF);

    mockMvc
        .perform(confirm(id, cash, CHF, "42,14", fuel, "42,14"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("not implemented yet")));

    assertThat(header(id, "state", String.class)).isEqualTo("processed");
    assertThat(header(id, "transaction_id", Long.class)).isNull();
  }

  @Test
  void confirmRefusesAnUnresolvedLineWithoutWriting() throws Exception {
    long cash = openAccount("Cash", EUR);
    long id = processedReceipt(cash, "42.14", EUR);

    mockMvc
        .perform(
            post("/receipts/" + id + "/confirm")
                .param("date", DAY)
                .param("accountId", String.valueOf(cash))
                .param("currencyCode", EUR)
                .param("total", "42,14")
                .param("lineDescription", "Mystery")
                .param("lineAmount", "42,14")
                .param("lineCategoryId", "")
                .param("lineCategoryType", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("no category yet")));

    assertThat(header(id, "state", String.class)).isEqualTo("processed");
  }

  @Test
  void confirmRefusesWhenTheLinesDoNotAddUpToTheTotal() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "50.00", EUR);

    mockMvc
        .perform(confirm(id, cash, "50,00", fuel, "42,14"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("do not add up")));

    assertThat(header(id, "state", String.class)).isEqualTo("processed");
  }

  // ── Reopen / re-enter ───────────────────────────────────────────────────────

  @Test
  void reopenReturnsToTheEditorOfferingReEntryAndLeavesTheTransactionAlone() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);
    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());
    Long transactionId = header(id, "transaction_id", Long.class);

    mockMvc
        .perform(post("/receipts/" + id + "/reopen"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Re-enter")))
        .andExpect(content().string(not(containsString("disabled=\"disabled\""))));

    assertThat(header(id, "state", String.class)).isEqualTo("processed");
    // Nothing is written but the state: the transaction and the link both stand.
    assertThat(header(id, "transaction_id", Long.class)).isEqualTo(transactionId);
    assertThat(isVoided(transactionId)).isFalse();
  }

  @Test
  void reEnteringVoidsThePredecessorAndRepointsTheLink() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);
    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());
    long first = header(id, "transaction_id", Long.class);
    mockMvc.perform(post("/receipts/" + id + "/reopen")).andExpect(status().isOk());

    mockMvc.perform(confirm(id, cash, "40,00", fuel, "40,00")).andExpect(status().isOk());

    long second = header(id, "transaction_id", Long.class);
    assertThat(second).isNotEqualTo(first);
    // The old version stays inspectable, soft-deleted (data-model §3.5).
    assertThat(isVoided(first)).isTrue();
    assertThat(isVoided(second)).isFalse();
    assertThat(legsOf(second)).containsExactlyInAnyOrder(leg(cash, "-40.00"), leg(fuel, "40.00"));
  }

  // ── The committed delete (the ladder's last rung) ───────────────────────────

  @Test
  void committedDeleteCanKeepTheTransactionAndStillUnlinksIt() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);
    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());
    long transactionId = header(id, "transaction_id", Long.class);

    mockMvc
        .perform(
            post("/receipts/" + id + "/delete-committed")
                .param("voidTransaction", "false")
                .param("removeFiles", "false"))
        .andExpect(status().is3xxRedirection());

    // The row keeps its transaction_id — unlinking is an effect of the soft delete, not a write.
    assertThat(header(id, "transaction_id", Long.class)).isEqualTo(transactionId);
    assertThat(header(id, "deleted_at", java.time.OffsetDateTime.class)).isNotNull();
    assertThat(isVoided(transactionId)).isFalse();
  }

  @Test
  void committedDeleteCanVoidTheTransactionToo() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);
    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());
    long transactionId = header(id, "transaction_id", Long.class);

    mockMvc
        .perform(post("/receipts/" + id + "/delete-committed").param("voidTransaction", "true"))
        .andExpect(status().is3xxRedirection());

    assertThat(isVoided(transactionId)).isTrue();
  }

  @Test
  void committedDeleteDialogOffersBothAxes() throws Exception {
    long cash = openAccount("Cash", EUR);
    long id = processedReceipt(cash, "42.14", EUR);

    mockMvc
        .perform(get("/receipts/" + id + "/delete-committed-confirm"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Keep the transaction and the files")))
        .andExpect(content().string(containsString("Void the transaction and delete the files")));
  }

  @Test
  void multiDeleteSkipsCommittedMembers() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long committed = processedReceipt(cash, "42.14", EUR);
    long open = processedReceipt(cash, "5.00", EUR);
    mockMvc.perform(confirm(committed, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());

    mockMvc
        .perform(
            post("/receipts/delete")
                .param("id", String.valueOf(committed), String.valueOf(open))
                .param("removeFiles", "false"))
        .andExpect(status().isOk());

    assertThat(header(committed, "deleted_at", java.time.OffsetDateTime.class)).isNull();
    assertThat(header(open, "deleted_at", java.time.OffsetDateTime.class)).isNotNull();
  }

  @Test
  void savingTheDraftOfCommittedReceiptIsRefused() throws Exception {
    long cash = openAccount("Cash", EUR);
    long fuel = category("Fuel", "expense");
    long id = processedReceipt(cash, "42.14", EUR);
    mockMvc.perform(confirm(id, cash, "42,14", fuel, "42,14")).andExpect(status().isOk());

    // The committed view disables the editor, but that is a display rule; the draft is the record
    // of what was booked, so the write path refuses regardless of what reaches it.
    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    post("/receipts/" + id + "/lines/save")
                        .param("date", DAY)
                        .param("accountId", String.valueOf(cash))
                        .param("currencyCode", EUR)
                        .param("total", "999,00")
                        .param("lineDescription", "Tampered")
                        .param("lineAmount", "999,00")))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "A committed receipt's draft is the record of what was booked — reopen it before"
                + " editing");

    assertThat(lineCount(id)).isEqualTo(1);
    assertThat(header(id, "total_amount", BigDecimal.class)).isEqualByComparingTo("42.14");
  }

  // ── The header retrofit (Note + Receipt no., plan §9g) ──────────────────────

  @Test
  void saveRoundTripsTheHeaderNoteAndReceiptNumber() throws Exception {
    long cash = openAccount("Cash", EUR);
    long id = processedReceipt(cash, "42.14", EUR);

    mockMvc
        .perform(
            base(id, cash, "42,14")
                .param("note", "Company car")
                .param("receiptNumber", "BEL-4711")
                .param("lineAmount", "42,14"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Company car")))
        .andExpect(content().string(containsString("BEL-4711")));
  }

  // ── seeding + helpers ───────────────────────────────────────────────────────

  /** A Save-shaped POST of the editor header (used by the header round-trip and gate cases). */
  private MockHttpServletRequestBuilder base(long id, long accountId, String total) {
    return post("/receipts/" + id + "/lines/save")
        .param("date", DAY)
        .param("accountId", String.valueOf(accountId))
        .param("currencyCode", EUR)
        .param("total", total);
  }

  /** A Confirm POST of a one-line draft, in the receipt's own (here: the account's) currency. */
  private MockHttpServletRequestBuilder confirm(
      long id, long accountId, String total, long categoryId, String lineAmount) {
    return confirm(id, accountId, EUR, total, categoryId, lineAmount);
  }

  /** A Confirm POST naming the header currency explicitly (the cross-currency refusal case). */
  private MockHttpServletRequestBuilder confirm(
      long id, long accountId, String currency, String total, long categoryId, String lineAmount) {
    return post("/receipts/" + id + "/confirm")
        .param("date", DAY)
        .param("accountId", String.valueOf(accountId))
        .param("currencyCode", currency)
        .param("total", total)
        .param("lineDescription", "Diesel")
        .param("categoryText", "Fuel")
        .param("lineCategoryId", String.valueOf(categoryId))
        .param("lineCategoryType", "expense")
        .param("lineAmount", lineAmount);
  }

  private long openAccount(String name, String currency) {
    return insertAccount(name, "asset", currency);
  }

  private long category(String name, String type) {
    return insertAccount(name, type, EUR);
  }

  private long lastReceiptId() {
    return jdbcClient
        .sql("select receipt_id from receipt order by receipt_id desc limit 1")
        .query(Long.class)
        .single();
  }

  /**
   * Stand in for the analyse step (a billed API call no suite may make — plan §9e): move a
   * pre-processed receipt to {@code processed} carrying the header the parser would have filled.
   */
  private void seedParseResult(long receiptId, long accountId, String total) {
    jdbcClient
        .sql(
            """
            update receipt set state = 'processed', account_id = :accountId,
                               total_amount = :total, currency_code = 'EUR', receipt_date = :day
            where receipt_id = :id
            """)
        .param("accountId", accountId)
        .param("total", new BigDecimal(total))
        .param("day", LocalDate.parse(DAY))
        .param("id", receiptId)
        .update();
  }

  private long insertAccount(String name, String type, String currency) {
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
                                 account_id, total_amount, currency_code, receipt_date)
            values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                    :accountId, :total, :currency, :day)
            returning receipt_id
            """)
        .param("accountId", accountId)
        .param("total", new BigDecimal(total))
        .param("currency", currency)
        .param("day", LocalDate.parse(DAY))
        .query(Long.class)
        .single();
  }

  private <T> T header(long receiptId, String column, Class<T> type) {
    return jdbcClient
        .sql("select " + column + " from receipt where receipt_id = :id")
        .param("id", receiptId)
        .query(type)
        .optional()
        .orElse(null);
  }

  private int lineCount(long receiptId) {
    return jdbcClient
        .sql("select count(*) from receipt_line where receipt_id = :id")
        .param("id", receiptId)
        .query(Integer.class)
        .single();
  }

  private boolean isVoided(long transactionId) {
    return jdbcClient
        .sql("select deleted_at is not null from transaction where transaction_id = :id")
        .param("id", transactionId)
        .query(Boolean.class)
        .single();
  }

  private String noteOf(long transactionId) {
    return jdbcClient
        .sql("select note from transaction where transaction_id = :id")
        .param("id", transactionId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /**
   * The transaction's legs as {@code "<accountId> <amount>"}, the amount normalised so the
   * assertion states cents rather than the column's stored scale.
   */
  private List<String> legsOf(long transactionId) {
    return jdbcClient
        .sql(
            "select account_id, amount from posting where transaction_id = :id order by account_id")
        .param("id", transactionId)
        .query((rs, n) -> leg(rs.getLong(1), rs.getBigDecimal(2).toPlainString()))
        .list();
  }

  private static String leg(long accountId, String amount) {
    return accountId + " " + new BigDecimal(amount).stripTrailingZeros().toPlainString();
  }
}
