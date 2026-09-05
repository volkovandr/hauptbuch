package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (import.md §12; CLAUDE.md §6): the ledger duplicate scan (plan f1) driven
 * through {@link ImportController} — the run button, the pending/adjudicated rendering, the
 * keep/skip actions, the fourth commit-gate condition, and the "stale ⇒ re-raise" behaviour when a
 * ledger transaction changes after the owner decided (Q-IMP-5). The detection SQL and the re-run
 * reconcile are covered in {@link ImportDuplicateScanSqlLogicTest}.
 *
 * <p>A one-file campaign is staged ({@link #DAY_MONTH_BANK}, two Food spends) and its account and
 * category mapped so the e4 issues gate is already clear — only the f1 condition is in play.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportDuplicateScanScreenIntegrationTest {

  private static final String DAY_MONTH_BANK =
      """
      !Type:Bank
      D01/07'2004
      T-12.34
      PGrocer
      LFood
      ^
      D28/07'2004
      T-5.00
      PBaker
      LFood
      ^
      """;

  private static final String RUN = "/import/review/duplicate-scan/run";
  private static final String REVIEW_ANCHOR = "/import/review#duplicate-scan";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  @Test
  void listsLedgerOverlapAndLocksTheGateUntilAdjudicated() throws Exception {
    MockHttpSession session = mappedCampaign();
    seedLedgerOverlap("2004-07-01", "-12.34", "12.34");

    String beforeRun = reviewHtml(session);
    assertThat(beforeRun).contains("Ledger duplicate scan");
    assertThat(beforeRun).contains("Not yet run.");
    assertThat(beforeRun).contains("is not cleared");
    assertThat(beforeRun).doesNotContain("the campaign is ready to commit");

    mockMvc.perform(post(RUN).session(session)).andExpect(redirectedUrl(REVIEW_ANCHOR));

    String afterRun = reviewHtml(session);
    assertThat(afterRun).contains("Possible duplicate:");
    assertThat(afterRun).contains("01.07.2004");
    assertThat(afterRun).contains("is not cleared");
    assertThat(afterRun).doesNotContain("the campaign is ready to commit");

    mockMvc
        .perform(
            post("/import/review/duplicate-scan/" + onlyMatchId() + "/adjudicate")
                .param("decision", "skip")
                .session(session))
        .andExpect(redirectedUrl(REVIEW_ANCHOR));

    String adjudicated = reviewHtml(session);
    assertThat(adjudicated).contains("match(es) already decided");
    assertThat(adjudicated).contains("the campaign is ready to commit");
    assertThat(adjudicated).doesNotContain("is not cleared");
  }

  @Test
  void runsCleanWhenNothingOverlaps() throws Exception {
    MockHttpSession session = mappedCampaign();

    mockMvc.perform(post(RUN).session(session)).andExpect(redirectedUrl(REVIEW_ANCHOR));

    String html = reviewHtml(session);
    assertThat(html).contains("No overlaps with the live ledger");
    assertThat(html).contains("the campaign is ready to commit");
  }

  @Test
  void adjudicatesImportAnywayToo() throws Exception {
    MockHttpSession session = mappedCampaign();
    seedLedgerOverlap("2004-07-01", "-12.34", "12.34");
    mockMvc.perform(post(RUN).session(session));

    mockMvc
        .perform(
            post("/import/review/duplicate-scan/" + onlyMatchId() + "/adjudicate")
                .param("decision", "import")
                .session(session))
        .andExpect(redirectedUrl(REVIEW_ANCHOR));

    assertThat(
            jdbcClient
                .sql("select adjudication from import_duplicate_match")
                .query(String.class)
                .single())
        .isEqualTo("import");
    assertThat(reviewHtml(session)).contains("the campaign is ready to commit");
  }

  @Test
  void rejectsAnUnknownDecision() throws Exception {
    MockHttpSession session = mappedCampaign();
    seedLedgerOverlap("2004-07-01", "-12.34", "12.34");
    mockMvc.perform(post(RUN).session(session));

    mockMvc
        .perform(
            post("/import/review/duplicate-scan/" + onlyMatchId() + "/adjudicate")
                .param("decision", "maybe")
                .session(session))
        .andExpect(redirectedUrl(REVIEW_ANCHOR));

    assertThat(
            jdbcClient
                .sql("select adjudication from import_duplicate_match")
                .query(String.class)
                .single())
        .isEqualTo("pending");
  }

  @Test
  void goesStaleAndReRaisesAnAdjudicationWhenTheLedgerChanges() throws Exception {
    MockHttpSession session = mappedCampaign();
    long ledgerTxn = seedLedgerOverlap("2004-07-01", "-12.34", "12.34");
    mockMvc.perform(post(RUN).session(session));
    mockMvc.perform(
        post("/import/review/duplicate-scan/" + onlyMatchId() + "/adjudicate")
            .param("decision", "skip")
            .session(session));

    // The owner edits that ledger transaction after deciding — updated_at moves past ran_at.
    jdbcClient
        .sql(
            "update transaction set updated_at = now() + interval '1 second'"
                + " where transaction_id = :id")
        .param("id", ledgerTxn)
        .update();

    String stale = reviewHtml(session);
    assertThat(stale)
        .contains("A ledger transaction has changed since the scan last ran — re-run it before");
    assertThat(stale).doesNotContain("the campaign is ready");

    mockMvc.perform(post(RUN).session(session));

    assertThat(reviewHtml(session)).contains("Possible duplicate:");
    assertThat(
            jdbcClient
                .sql("select adjudication from import_duplicate_match")
                .query(String.class)
                .single())
        .isEqualTo("pending");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /** Stage {@link #DAY_MONTH_BANK} for "Current Account" and map its account + "Food" category. */
  private MockHttpSession mappedCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session)).andExpect(redirectedUrl("/import"));
    uploadAndStage(session);
    long giro = insertAccount("Giro", "asset");
    long food = insertAccount("Food", "expense");
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId("Current Account") + "/map")
            .param("accountId", Long.toString(giro))
            .session(session));
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId("Current Account") + "/expect-file")
            .param("expectFile", "false")
            .session(session));
    mockMvc.perform(
        post("/import/review/categories/" + categoryRowId("Food") + "/map")
            .param("accountId", Long.toString(food))
            .session(session));
    return session;
  }

  private void uploadAndStage(MockHttpSession session) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/import/uploads")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "current.qif",
                            "text/plain",
                            DAY_MONTH_BANK.getBytes(StandardCharsets.UTF_8)))
                    .session(session))
            .andReturn();
    String location =
        Objects.requireNonNull(result.getResponse().getRedirectedUrl(), "no redirect Location");
    String token = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(
        post("/import/uploads/" + token)
            .param("moneyAccountName", "Current Account")
            .session(session));
    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import"));
  }

  private long seedLedgerOverlap(String date, String fundingAmount, String categoryAmount) {
    long txn =
        jdbcClient
            .sql(
                "insert into transaction (date) values (cast(:d as date)) returning transaction_id")
            .param("d", date)
            .query(Long.class)
            .single();
    ledgerPosting(txn, mappedAccountId("Current Account"), fundingAmount);
    ledgerPosting(txn, mappedCategoryAccountId("Food"), categoryAmount);
    return txn;
  }

  private void ledgerPosting(long transactionId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long accountRowId(String moneyAccountName) {
    return jdbcClient
        .sql("select import_account_id from import_account where money_account_name = :n")
        .param("n", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private long mappedAccountId(String moneyAccountName) {
    return jdbcClient
        .sql("select account_id from import_account where money_account_name = :n")
        .param("n", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private long categoryRowId(String moneyPath) {
    return jdbcClient
        .sql("select import_category_id from import_category where money_path = :p")
        .param("p", moneyPath)
        .query(Long.class)
        .single();
  }

  private long mappedCategoryAccountId(String moneyPath) {
    return jdbcClient
        .sql("select account_id from import_category where money_path = :p")
        .param("p", moneyPath)
        .query(Long.class)
        .single();
  }

  private long onlyMatchId() {
    return jdbcClient
        .sql("select import_duplicate_match_id from import_duplicate_match")
        .query(Long.class)
        .single();
  }

  private String reviewHtml(MockHttpSession session) throws Exception {
    return mockMvc
        .perform(get("/import/review").session(session))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
