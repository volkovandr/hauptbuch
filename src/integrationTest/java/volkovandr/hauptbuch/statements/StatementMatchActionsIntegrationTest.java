package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;

/**
 * Integration tier (CLAUDE.md §6): the match actions of slice c2 driven through MockMvc against
 * real Postgres, on the sample CSV of {@link StatementFixtures} — Accept all exact, a pick,
 * Unmatch, the overlap decision, delete with the keep/reset question, and the ledger's side effects
 * (a pending transaction is confirmed, an amount edit drops the match). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementMatchActionsIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired LedgerService ledgerService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long foodId;
  private long statementId;
  private long shopTxn;
  private long shopLeg;
  private long salaryTxn;
  private long salaryLeg;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency("EUR");
    accountId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    accountService.updateDetection(accountId, "XX00 1111 2222", false);
    foodId = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();
    long incomeId = accountService.insertLeaf("Salary", "income", null, "EUR").accountId();
    shopTxn = book("2026-05-01", "-12.50", foodId, "confirmed");
    salaryTxn = book("2026-05-05", "1234.56", incomeId, "pending_review");
    shopLeg = legOn(shopTxn);
    salaryLeg = legOn(salaryTxn);
    long profile = StatementFixtures.saveProfile(mockMvc, jdbcClient);
    statementId = StatementFixtures.uploadAndCreate(mockMvc, profile, accountId);
  }

  private long book(String date, String amount, long counterAccount, String lifecycle) {
    BigDecimal value = new BigDecimal(amount);
    return ledgerService.recordTransaction(
        new TransactionDraft(
            LocalDate.parse(date),
            null,
            null,
            lifecycle,
            List.of(
                PostingDraft.of(accountId, value),
                PostingDraft.of(counterAccount, value.negate()))));
  }

  private long legOn(long transactionId) {
    return jdbcClient
        .sql("select posting_id from posting where transaction_id = :t and account_id = :a")
        .param("t", transactionId)
        .param("a", accountId)
        .query(Long.class)
        .single();
  }

  private long lineWithAmount(String amount, String bookingDate) {
    return jdbcClient
        .sql(
            "select statement_line_id from statement_line where statement_id = :s and amount = :a"
                + " and booking_date = :d")
        .param("s", statementId)
        .param("a", new BigDecimal(amount))
        .param("d", LocalDate.parse(bookingDate))
        .query(Long.class)
        .single();
  }

  private long lineWithAmount(String amount) {
    return jdbcClient
        .sql("select statement_line_id from statement_line where statement_id = :s and amount = :a")
        .param("s", statementId)
        .param("a", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private String reconciliationOf(long postingId) {
    return jdbcClient
        .sql("select reconciliation from posting where posting_id = :p")
        .param("p", postingId)
        .query(String.class)
        .single();
  }

  private long matchCount() {
    return jdbcClient.sql("select count(*) from statement_match").query(Long.class).single();
  }

  private String lifecycleOf(long transactionId) {
    return jdbcClient
        .sql("select lifecycle from transaction where transaction_id = :t")
        .param("t", transactionId)
        .query(String.class)
        .single();
  }

  private void acceptAll(int expectedMatched) throws Exception {
    mockMvc
        .perform(post("/statements/" + statementId + "/accept-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            flash()
                .attribute(
                    "notice",
                    expectedMatched
                        + (expectedMatched == 1 ? " line matched." : " lines matched.")));
  }

  @Test
  void pageOffersAcceptAllAndPerLineAccept() throws Exception {
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Accept all exact (")))
        .andExpect(
            content().string(containsString("/lines/" + lineWithAmount("-12.50") + "/accept")));
  }

  @Test
  void actionFormsPostWithHtmxAndSwapTheMainElementSoTheScrollPositionStays() throws Exception {
    String page = statementPage();

    assertThat(page)
        .contains("hx-post=\"/statements/" + statementId + "/accept-all\"")
        .contains(
            "hx-post=\"/statements/"
                + statementId
                + "/lines/"
                + lineWithAmount("-12.50")
                + "/accept\"")
        .contains("hx-post=\"/statements/" + statementId + "/lines\"")
        .contains("hx-select=\"main\"")
        .contains("hx-swap=\"outerHTML\"");
    assertThat(page).doesNotContain("hx-post=\"/statements/" + statementId + "/delete\"");
  }

  @Test
  void postingFittingTwoLinesIsOfferedToBothAndAcceptAllSkipsThem() throws Exception {
    long secondShopLine = secondLineForTheShopLeg();
    long firstShopLine = lineWithAmount("-12.50", "2026-05-02");

    assertThat(statementPage())
        .contains("/lines/" + firstShopLine + "/accept")
        .contains("/lines/" + secondShopLine + "/accept")
        .contains("fits another line too");

    acceptAll(1);

    assertThat(reconciliationOf(shopLeg)).isEqualTo("unreconciled");

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + secondShopLine + "/accept")
                .param("posting", String.valueOf(shopLeg)))
        .andExpect(status().is3xxRedirection());

    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
    assertThat(statementPage()).doesNotContain("/lines/" + firstShopLine + "/accept");
  }

  /** A second line of the statement with the shop leg's amount, booked two days after the first. */
  private long secondLineForTheShopLeg() {
    return jdbcClient
        .sql(
            "insert into statement_line (statement_id, sort_order, booking_date, amount,"
                + " counterparty, raw_text) values (:s, 9, '2026-05-04', -12.50, 'ShopAaa', 'r')"
                + " returning statement_line_id")
        .param("s", statementId)
        .query(Long.class)
        .single();
  }

  @Test
  void acceptAllExactReconcilesTheLegsAndConfirmsThePendingTransaction() throws Exception {
    acceptAll(2);

    assertThat(matchCount()).isEqualTo(2);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
    assertThat(reconciliationOf(salaryLeg)).isEqualTo("reconciled");
    assertThat(lifecycleOf(salaryTxn)).isEqualTo("confirmed");
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("Unmatch")))
        .andExpect(content().string(containsString("/register?selected=" + shopTxn)))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Accept all exact"))));
  }

  @Test
  void needsAttentionViewHidesMatchedLinesAndIsRememberedForTheSession() throws Exception {
    acceptAll(2);
    org.springframework.mock.web.MockHttpSession session =
        new org.springframework.mock.web.MockHttpSession();
    String matchedLine = "ShopAaa · Card payment";

    mockMvc
        .perform(get("/statements/" + statementId).session(session))
        .andExpect(content().string(containsString(matchedLine)));
    mockMvc
        .perform(get("/statements/" + statementId).param("show", "attention").session(session))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString(matchedLine))))
        .andExpect(content().string(containsString("ShopBbb · Abroad")))
        .andExpect(content().string(containsString("Needs attention (<span>2</span>)")));
    mockMvc
        .perform(get("/statements/" + statementId).session(session))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString(matchedLine))));
    mockMvc
        .perform(get("/statements/" + statementId).param("show", "all").session(session))
        .andExpect(content().string(containsString(matchedLine)));
  }

  @Test
  void needsAttentionViewKeepsTheLineTheOperatorJustClicked() throws Exception {
    acceptAll(2);
    org.springframework.mock.web.MockHttpSession session =
        new org.springframework.mock.web.MockHttpSession();

    mockMvc
        .perform(
            get("/statements/" + statementId)
                .param("show", "attention")
                .param("dock", String.valueOf(lineWithAmount("-12.50")))
                .session(session))
        .andExpect(content().string(containsString("ShopAaa · Card payment")))
        .andExpect(content().string(containsString("role=\"alert\"")));
  }

  @Test
  void statementContentsEditorIsCollapsedUnderSummary() throws Exception {
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("<details class=\"statement-contents\">")))
        .andExpect(content().string(containsString("Edit statement contents")));
  }

  @Test
  void acceptOneLineMatchesJustThatLine() throws Exception {
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/accept")
                .param("posting", String.valueOf(shopLeg)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attribute("notice", "Line matched."));

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
    assertThat(reconciliationOf(salaryLeg)).isEqualTo("unreconciled");
  }

  @Test
  void acceptingPostingThatIsNotCandidateIsRefused() throws Exception {
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/accept")
                .param("posting", String.valueOf(salaryLeg)))
        .andExpect(flash().attributeExists("error"));

    assertThat(matchCount()).isZero();
  }

  @Test
  void unmatchSetsThePostingBackToUnreconciled() throws Exception {
    acceptAll(2);

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/unmatch"))
        .andExpect(flash().attributeExists("notice"));

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("unreconciled");
    assertThat(reconciliationOf(salaryLeg)).isEqualTo("reconciled");
  }

  @Test
  void unmatchOnOneStatementLeavesAnotherStatementsMatchAndTheReconciliation() throws Exception {
    earlierStatementMatching(shopLeg);
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/accept")
                .param("posting", String.valueOf(shopLeg)))
        .andExpect(status().is3xxRedirection());
    assertThat(matchCount()).isEqualTo(2);

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/unmatch"))
        .andExpect(status().is3xxRedirection());

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
  }

  @Test
  void savingChangedAmountOnMatchedLineUnmatchesItButSavingTextDoesNot() throws Exception {
    acceptAll(2);
    long shopLine = lineWithAmount("-12.50");

    saveLine(shopLine, "2026-05-02", "-12,50", "Renamed");
    assertThat(matchCount()).isEqualTo(2);

    saveLine(shopLine, "2026-05-02", "-13,00", "Renamed");

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("unreconciled");
    assertThat(reconciliationOf(salaryLeg)).isEqualTo("reconciled");
  }

  @Test
  void pageWarnsBesideSaveLinesOnlyWhenLineIsMatched() throws Exception {
    assertThat(statementPage()).doesNotContain("makes its posting");

    acceptAll(2);

    assertThat(statementPage()).contains("makes its posting");
  }

  private String statementPage() throws Exception {
    return mockMvc
        .perform(get("/statements/" + statementId))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void saveLine(long lineId, String bookingDate, String amount, String counterparty)
      throws Exception {
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines")
                .param("line", String.valueOf(lineId))
                .param("bookingDate_" + lineId, bookingDate)
                .param("valueDate_" + lineId, "")
                .param("amount_" + lineId, amount)
                .param("counterparty_" + lineId, counterparty)
                .param("description_" + lineId, "")
                .param("bankCategory_" + lineId, ""))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void editingTheAmountOfMatchedLegDropsTheMatch() throws Exception {
    acceptAll(2);

    ledgerService.editTransaction(
        shopTxn,
        new TransactionDraft(
            LocalDate.parse("2026-05-01"),
            null,
            null,
            "confirmed",
            List.of(
                PostingDraft.of(accountId, new BigDecimal("-13.00")),
                PostingDraft.of(foodId, new BigDecimal("13.00")))));

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("unreconciled");
  }

  @Test
  void editingOnlyTheDateOfMatchedTransactionDropsTheMatchAndTheReconciliation() throws Exception {
    acceptAll(2);

    ledgerService.editTransaction(
        shopTxn,
        new TransactionDraft(
            LocalDate.parse("2026-05-02"),
            null,
            null,
            "confirmed",
            List.of(
                PostingDraft.of(accountId, new BigDecimal("-12.50")),
                PostingDraft.of(foodId, new BigDecimal("12.50")))));

    assertThat(matchCount()).isEqualTo(1);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("unreconciled");
    assertThat(reconciliationOf(salaryLeg)).isEqualTo("reconciled");
  }

  @Test
  void overlapOffersSameMovementOrDifferentTransaction() throws Exception {
    long earlier = earlierStatementMatching(shopLeg);
    String page =
        mockMvc
            .perform(get("/statements/" + statementId))
            .andExpect(content().string(containsString("Same movement")))
            .andExpect(content().string(containsString("Different transaction")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page)
        .contains("already on another statement")
        .contains("Match the transaction to this statement as well.")
        .contains("Leave the transaction for the other statement.");

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/different")
                .param("posting", String.valueOf(shopLeg)))
        .andExpect(flash().attributeExists("notice"));

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("statement-status--missing")));
    assertThat(matchCount()).isEqualTo(1);
    assertThat(earlier).isPositive();
  }

  @Test
  void sameMovementMatchesThePostingAgainWithoutChangingItsState() throws Exception {
    earlierStatementMatching(shopLeg);

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + lineWithAmount("-12.50") + "/accept")
                .param("posting", String.valueOf(shopLeg)))
        .andExpect(flash().attribute("notice", "Line matched."));

    assertThat(matchCount()).isEqualTo(2);
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
  }

  @Test
  void deleteKeepingReconciledRemovesTheMatchesOnly() throws Exception {
    acceptAll(2);

    mockMvc
        .perform(post("/statements/" + statementId + "/delete").param("reconciliation", "keep"))
        .andExpect(redirectedUrl("/statements"));

    assertThat(matchCount()).isZero();
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
  }

  @Test
  void deleteWithResetUnreconcilesExceptWhatAnotherStatementStillMatches() throws Exception {
    earlierStatementMatching(shopLeg);
    acceptAll(1);

    mockMvc
        .perform(post("/statements/" + statementId + "/delete").param("reconciliation", "reset"))
        .andExpect(redirectedUrl("/statements"));

    assertThat(reconciliationOf(salaryLeg)).isEqualTo("unreconciled");
    assertThat(reconciliationOf(shopLeg)).isEqualTo("reconciled");
    assertThat(matchCount()).isEqualTo(1);
  }

  /** A second statement of the account whose only line is matched to {@code postingId}. */
  private long earlierStatementMatching(long postingId) {
    long profile =
        jdbcClient
            .sql("select statement_profile_id from statement where statement_id = :s")
            .param("s", statementId)
            .query(Long.class)
            .single();
    long earlier =
        jdbcClient
            .sql(
                "insert into statement (statement_profile_id, account_id, state,"
                    + " original_filename, file_path, period_start, period_end)"
                    + " values (:p, :a, 'new', 'old.csv', 'x', '2026-04-15', '2026-05-15')"
                    + " returning statement_id")
            .param("p", profile)
            .param("a", accountId)
            .query(Long.class)
            .single();
    long line =
        jdbcClient
            .sql(
                "insert into statement_line (statement_id, sort_order, booking_date, amount,"
                    + " counterparty) values (:s, 0, '2026-05-02', -12.50, 'ShopAaa')"
                    + " returning statement_line_id")
            .param("s", earlier)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into statement_match (statement_line_id, statement_id, posting_id)"
                + " values (:l, :s, :p)")
        .param("l", line)
        .param("s", earlier)
        .param("p", postingId)
        .update();
    jdbcClient
        .sql("update posting set reconciliation = 'reconciled' where posting_id = :p")
        .param("p", postingId)
        .update();
    return earlier;
  }
}
