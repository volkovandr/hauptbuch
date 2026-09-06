package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
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
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (import.md §10, §12; CLAUDE.md §6): {@link ImportCommitService} booking a whole
 * staged campaign into a real Postgres ledger (plan f2a). A two-file campaign — each file an
 * opening balance, a spend, and the two sightings of a same-currency transfer — is staged and
 * mapped through {@link ImportController}, the duplicate scan run, then {@link
 * ImportCommitService#commit} called directly (the screen is f2b). Asserts the booked
 * transactions/postings, that account balances match the e′ statistics, the {@code skip}
 * adjudication, opening-balance {@code take_money}, and that a broken map refuses without booking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportCommitIntegrationTest {

  private static final String BANK_AAA_QIF =
      """
      !Type:Bank
      D01/07'2004
      T1000.00
      POpening Balance
      L[BankAaa]
      ^
      D15/07'2004
      T-12.34
      PShopBbb
      LFood
      ^
      D20/07'2004
      T-100.00
      PTransfer to BankBbb
      L[BankBbb]
      ^
      """;

  private static final String BANK_BBB_QIF =
      """
      !Type:Bank
      D02/07'2004
      T500.00
      POpening Balance
      L[BankBbb]
      ^
      D20/07'2004
      T100.00
      PTransfer from BankAaa
      L[BankAaa]
      ^
      D25/07'2004
      T-50.00
      PLandlord
      LRent
      ^
      """;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired ImportCommitService importCommitService;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency("EUR");
  }

  @Test
  void commitsTheCampaignAndBalancesMatchTheStatistics() throws Exception {
    MockHttpSession session = mappedCampaign();
    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));

    ImportCommitService.CommitResult result = importCommitService.commit(n -> {});
    long bankAaa = mappedAccountId("BankAaa");
    long bankBbb = mappedAccountId("BankBbb");

    assertThat(result.booked())
        .isEqualTo(3); // two spends + one transfer sighting (the mirror is excluded)
    assertThat(result.openingBalances()).isEqualTo(2);

    // Every booked transaction balances.
    assertThat(
            jdbcClient
                .sql(
                    "select count(*) from ("
                        + "  select transaction_id from posting group by transaction_id"
                        + "  having sum(amount) <> 0) unbalanced")
                .query(Integer.class)
                .single())
        .isZero();

    // Balances tick against the e′ per-account net sums (opening + spends + transfer).
    assertThat(balanceOf(bankAaa)).isEqualByComparingTo("887.66");
    assertThat(balanceOf(bankBbb)).isEqualByComparingTo("550.00");

    assertThat(sessionState()).isEqualTo("committed");

    importCommitService.clearStaging(currentOrCommittedSessionId());
    assertThat(jdbcClient.sql("select count(*) from import_file").query(Integer.class).single())
        .isZero();
    assertThat(
            jdbcClient.sql("select count(*) from import_transaction").query(Integer.class).single())
        .isZero();
  }

  @Test
  void skippedTransactionIsNotBooked() throws Exception {
    MockHttpSession session = mappedCampaign();
    long giroBbb = mappedAccountId("BankBbb");
    long rent = mappedCategoryAccountId("Rent");
    // A hand-entered ledger transaction that overlaps the staged Rent spend (date + funding +
    // amount
    // + category), so the scan raises it for a real adjudication.
    long overlap =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (date '2004-07-25', 'confirmed')"
                    + " returning transaction_id")
            .query(Long.class)
            .single();
    ledgerPosting(overlap, giroBbb, "-50.00");
    ledgerPosting(overlap, rent, "50.00");

    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));
    long matchId =
        jdbcClient
            .sql("select import_duplicate_match_id from import_duplicate_match")
            .query(Long.class)
            .single();
    mockMvc.perform(
        post("/import/review/duplicate-scan/" + matchId + "/adjudicate")
            .param("decision", "skip")
            .session(session));

    ImportCommitService.CommitResult result = importCommitService.commit(n -> {});

    assertThat(result.skipped()).isEqualTo(1);
    // The staged Rent spend was never booked: the only posting on Rent is the hand-entered overlap.
    assertThat(
            jdbcClient
                .sql("select count(*) from posting where account_id = :a")
                .param("a", rent)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void openingBalanceTakeMoneyVoidsHauptbuchsAndBooksMoneys() throws Exception {
    MockHttpSession session = mappedCampaign();
    long bankAaa = mappedAccountId("BankAaa");
    // Hauptbuch's own opening balance for that account, later than Money's.
    long obLeaf =
        accountService
            .findLeafUnderParentNamed("Opening Balances", "EUR")
            .orElseThrow()
            .accountId();
    long hbOpening =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (date '2005-01-01', 'confirmed')"
                    + " returning transaction_id")
            .query(Long.class)
            .single();
    ledgerPosting(hbOpening, bankAaa, "42.00");
    ledgerPosting(hbOpening, obLeaf, "-42.00");
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId("BankAaa") + "/opening-balance")
            .param("choice", "take_money")
            .session(session));
    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));

    importCommitService.commit(n -> {});

    assertThat(
            jdbcClient
                .sql("select deleted_at from transaction where transaction_id = :t")
                .param("t", hbOpening)
                .query(java.time.OffsetDateTime.class)
                .optional())
        .isPresent();
    assertThat(balanceOf(bankAaa))
        .isEqualByComparingTo("887.66"); // Money's 1000, not Hauptbuch's 42
  }

  @Test
  void refusesAndBooksNothingWhenTheGateReClosesOnAnUnmappedCategory() throws Exception {
    MockHttpSession session = oneFileCampaign();
    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));
    // Un-map the Food row after the review looked clear — the gate must catch it.
    jdbcClient
        .sql("update import_category set account_id = null where money_path = 'Food'")
        .update();

    assertThatThrownBy(() -> importCommitService.commit(n -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready to commit");

    assertThat(jdbcClient.sql("select count(*) from transaction").query(Integer.class).single())
        .isZero();
    assertThat(sessionState()).isEqualTo("open");
    assertThat(
            jdbcClient.sql("select count(*) from import_transaction").query(Integer.class).single())
        .isPositive();
  }

  // ── campaign setup ──────────────────────────────────────────────────────

  private MockHttpSession mappedCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session));
    stage(session, "bankaaa.qif", BANK_AAA_QIF, "BankAaa");
    stage(session, "bankbbb.qif", BANK_BBB_QIF, "BankBbb");

    mapAccount(session, "BankAaa", openAsset("Giro AAA"));
    mapAccount(session, "BankBbb", openAsset("Giro BBB"));
    mapCategory(session, "Food", insertExpense("Food"));
    mapCategory(session, "Rent", insertExpense("Rent"));
    return session;
  }

  private MockHttpSession oneFileCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session));
    String bankAaaSolo =
        """
        !Type:Bank
        D15/07'2004
        T-12.34
        PShopBbb
        LFood
        ^
        """;
    stage(session, "solo.qif", bankAaaSolo, "BankAaa");
    mapAccount(session, "BankAaa", openAsset("Giro AAA"));
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId("BankAaa") + "/expect-file")
            .param("expectFile", "false")
            .session(session));
    mapCategory(session, "Food", insertExpense("Food"));
    return session;
  }

  private void stage(MockHttpSession session, String filename, String qif, String moneyAccountName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/import/uploads")
                    .file(
                        new MockMultipartFile(
                            "file", filename, "text/plain", qif.getBytes(StandardCharsets.UTF_8)))
                    .session(session))
            .andReturn();
    String location =
        Objects.requireNonNull(result.getResponse().getRedirectedUrl(), "no redirect Location");
    String token = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(
        post("/import/uploads/" + token)
            .param("moneyAccountName", moneyAccountName)
            .session(session));
    mockMvc.perform(post("/import/uploads/" + token + "/stage").session(session));
  }

  private void mapAccount(MockHttpSession session, String moneyName, long accountId)
      throws Exception {
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId(moneyName) + "/map")
            .param("accountId", Long.toString(accountId))
            .session(session));
  }

  private void mapCategory(MockHttpSession session, String path, long accountId) throws Exception {
    mockMvc.perform(
        post("/import/review/categories/" + categoryRowId(path) + "/map")
            .param("accountId", Long.toString(accountId))
            .session(session));
  }

  private void ledgerPosting(long transactionId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :m)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("m", new BigDecimal(amount))
        .update();
  }

  private long openAsset(String name) {
    return accountService.insertLeaf(name, "asset", null, "EUR").accountId();
  }

  private long insertExpense(String name) {
    return accountService.insertLeaf(name, "expense", null, "EUR").accountId();
  }

  // ── reads ──────────────────────────────────────────────────────────────

  private BigDecimal balanceOf(long accountId) {
    return jdbcClient
        .sql(
            "select coalesce(sum(p.amount), 0) from posting p"
                + " join transaction t on t.transaction_id = p.transaction_id"
                + " where p.account_id = :a and t.deleted_at is null")
        .param("a", accountId)
        .query(BigDecimal.class)
        .single();
  }

  private String sessionState() {
    return jdbcClient
        .sql("select state from import_session order by import_session_id desc limit 1")
        .query(String.class)
        .single();
  }

  private long currentOrCommittedSessionId() {
    return jdbcClient
        .sql("select import_session_id from import_session order by import_session_id desc limit 1")
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
}
