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
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the rest of the statement dock (slice d2) through MockMvc
 * against real Postgres — an amount-differs or wrong-account transaction amended to the bank's
 * figures and matched, an extra's Edit, Move and Void, and the register dock's reconciled-leg
 * notice. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementDockAmendIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long otherAccountId;
  private long foodId;
  private long statementId;
  private long shopLine;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency("EUR");
    accountId = bank("BankAaa-EUR");
    accountService.updateDetection(accountId, "XX00 1111 2222", false);
    otherAccountId = bank("BankBbb-EUR");
    foodId = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();
    long profile = StatementFixtures.saveProfile(mockMvc, jdbcClient);
    statementId = StatementFixtures.uploadAndCreate(mockMvc, profile, accountId);
    shopLine =
        jdbcClient
            .sql(
                "select statement_line_id from statement_line where statement_id = :s and amount ="
                    + " -12.50")
            .param("s", statementId)
            .query(Long.class)
            .single();
  }

  private long bank(String name) {
    return accountService
        .openAccount(
            new AccountDraft(
                name, "asset", null, "EUR", LocalDate.parse("2026-01-01"), BigDecimal.ZERO))
        .accountId();
  }

  /** A confirmed transaction of {@code amount} out of {@code account} into Food; returns its id. */
  private long booked(long account, String date, String payee, String amount) {
    long payeeId =
        jdbcClient
            .sql("insert into payee (name) values (:n) returning payee_id")
            .param("n", payee)
            .query(Long.class)
            .single();
    long transaction =
        jdbcClient
            .sql(
                "insert into transaction (date, payee_id, lifecycle) values (:d, :p, 'confirmed')"
                    + " returning transaction_id")
            .param("d", LocalDate.parse(date))
            .param("p", payeeId)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount) values (:t, :a, :m), (:t, :f,"
                + " :n)")
        .param("t", transaction)
        .param("a", account)
        .param("m", new BigDecimal(amount).negate())
        .param("f", foodId)
        .param("n", new BigDecimal(amount))
        .update();
    return transaction;
  }

  private long legOf(long transaction, long account) {
    return jdbcClient
        .sql("select posting_id from posting where transaction_id = :t and account_id = :a")
        .param("t", transaction)
        .param("a", account)
        .query(Long.class)
        .single();
  }

  private BigDecimal amountOf(long posting) {
    return jdbcClient
        .sql("select amount from posting where posting_id = :p")
        .param("p", posting)
        .query(BigDecimal.class)
        .single();
  }

  private long count(String sql) {
    return jdbcClient.sql(sql).query(Long.class).single();
  }

  private String lineUrl(String action) {
    return "/statements/" + statementId + "/lines/" + shopLine + "/" + action;
  }

  @Test
  void amountDifferingTransactionOffersAmendAndTheDockExplainsTheChange() throws Exception {
    long leg = legOf(booked(accountId, "2026-05-02", "ShopAaa", "12.00"), accountId);

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Amend:")))
        .andExpect(content().string(containsString("posting=" + leg)));

    mockMvc
        .perform(
            get("/statements/" + statementId)
                .param("dock", String.valueOf(shopLine))
                .param("posting", String.valueOf(leg)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Amend transaction")))
        .andExpect(content().string(containsString("saving sets it to the bank")))
        .andExpect(content().string(containsString(lineUrl("amend/" + leg))))
        .andExpect(content().string(containsString("value=\"12,50\"")));
  }

  @Test
  void amendSetsTheLegToTheBanksAmountAndMatchesItReconciled() throws Exception {
    long transaction = booked(accountId, "2026-05-02", "ShopAaa", "12.00");
    long leg = legOf(transaction, accountId);

    mockMvc
        .perform(
            post(lineUrl("amend/" + leg))
                .param("date", "2026-05-02")
                .param("payeeText", "ShopAaa")
                .param("categoryId", String.valueOf(foodId)))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attribute("notice", "Transaction amended and matched."));

    assertThat(amountOf(legOf(transaction, accountId))).isEqualByComparingTo("-12.50");
    assertThat(
            count(
                "select count(*) from statement_match m join posting p on p.posting_id ="
                    + " m.posting_id where m.statement_line_id = "
                    + shopLine
                    + " and p.reconciliation = 'reconciled'"))
        .isEqualTo(1);
    assertThat(count("select count(*) from transaction")).isEqualTo(1);
    assertThat(
            jdbcClient
                .sql("select sum(amount) from posting where transaction_id = :t")
                .param("t", transaction)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("0");
  }

  @Test
  void wrongAccountTransactionMovesToTheStatementsAccountOnSave() throws Exception {
    long transaction = booked(otherAccountId, "2026-05-02", "ShopAaa", "12.50");
    long leg = legOf(transaction, otherAccountId);

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("Move here:")));
    mockMvc
        .perform(
            get("/statements/" + statementId)
                .param("dock", String.valueOf(shopLine))
                .param("posting", String.valueOf(leg)))
        .andExpect(content().string(containsString("saving moves it to BankAaa-EUR")));

    mockMvc
        .perform(
            post(lineUrl("amend/" + leg))
                .param("date", "2026-05-02")
                .param("payeeText", "ShopAaa")
                .param("categoryId", String.valueOf(foodId)))
        .andExpect(flash().attribute("notice", "Transaction amended and matched."));

    assertThat(count("select count(*) from posting where account_id = " + otherAccountId)).isZero();
    assertThat(amountOf(legOf(transaction, accountId))).isEqualByComparingTo("-12.50");
    assertThat(count("select count(*) from statement_match")).isEqualTo(1);
  }

  @Test
  void refusedAmendChangesAndMatchesNothingAndReopensTheDock() throws Exception {
    long transaction = booked(accountId, "2026-05-02", "ShopAaa", "12.00");
    long leg = legOf(transaction, accountId);

    mockMvc
        .perform(
            post(lineUrl("amend/" + leg)).param("date", "2026-05-02").param("payeeText", "Typed"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("A category, transfer target, or person")))
        .andExpect(content().string(containsString("value=\"Typed\"")))
        .andExpect(content().string(containsString("Amend transaction")));

    assertThat(amountOf(leg)).isEqualByComparingTo("-12.00");
    assertThat(count("select count(*) from statement_match")).isZero();
  }

  @Test
  void anExtraOpensInTheDockAndEditingItMatchesNothing() throws Exception {
    long leg = legOf(booked(accountId, "2026-05-03", "ShopZzz", "4.00"), accountId);

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("extra=" + leg)));
    mockMvc
        .perform(get("/statements/" + statementId).param("extra", String.valueOf(leg)))
        .andExpect(content().string(containsString("Edit transaction")))
        .andExpect(content().string(containsString("name=\"amount\"")))
        .andExpect(content().string(containsString("value=\"4,00\"")))
        .andExpect(content().string(containsString("value=\"ShopZzz\"")));

    mockMvc
        .perform(
            post("/statements/" + statementId + "/extras/" + leg + "/edit")
                .param("date", "2026-05-03")
                .param("payeeText", "ShopZzz")
                .param("categoryId", String.valueOf(foodId))
                .param("amount", "5,00"))
        .andExpect(flash().attribute("notice", "Transaction saved."));

    assertThat(amountOf(leg)).isEqualByComparingTo("-5.00");
    assertThat(count("select count(*) from statement_match")).isZero();
  }

  @Test
  void anExtraMovesToAnotherAccountOfTheSameCurrency() throws Exception {
    long transaction = booked(accountId, "2026-05-03", "ShopZzz", "4.00");
    long leg = legOf(transaction, accountId);

    mockMvc
        .perform(
            post("/statements/" + statementId + "/extras/" + leg + "/move")
                .param("account", String.valueOf(otherAccountId)))
        .andExpect(flash().attribute("notice", "Transaction moved."));

    assertThat(amountOf(legOf(transaction, otherAccountId))).isEqualByComparingTo("-4.00");
    assertThat(count("select count(*) from posting where account_id = " + accountId)).isZero();
  }

  @Test
  void anExtraIsVoidedFromThePage() throws Exception {
    long transaction = booked(accountId, "2026-05-03", "ShopZzz", "4.00");
    long leg = legOf(transaction, accountId);

    mockMvc
        .perform(post("/statements/" + statementId + "/extras/" + leg + "/void"))
        .andExpect(flash().attribute("notice", "Transaction voided."));

    assertThat(
            count(
                "select count(*) from transaction where transaction_id = "
                    + transaction
                    + " and deleted_at is not null"))
        .isEqualTo(1);
  }

  @Test
  void extraThatIsGoneIsRefusedWithMessage() throws Exception {
    mockMvc
        .perform(post("/statements/" + statementId + "/extras/999999/void"))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attributeExists("error"));
  }

  @Test
  void registerDockNamesTheStatementThatReconciledLeg() throws Exception {
    mockMvc.perform(
        post(lineUrl("create"))
            .param("date", "2026-05-02")
            .param("categoryId", String.valueOf(foodId)));
    long transaction = count("select transaction_id from transaction");

    mockMvc
        .perform(get("/register/edit/" + transaction))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("BankAaa-EUR leg reconciled — statement 2026-05")));
  }

  @Test
  void registerDockKeepsTheReconciledNoticeAfterRefusedSave() throws Exception {
    mockMvc.perform(
        post(lineUrl("create"))
            .param("date", "2026-05-02")
            .param("categoryId", String.valueOf(foodId)));
    long transaction = count("select transaction_id from transaction");

    mockMvc
        .perform(
            post("/register/entry")
                .param("transactionId", String.valueOf(transaction))
                .param("accountId", String.valueOf(accountId))
                .param("date", "2026-05-02"))
        .andExpect(content().string(containsString("category, transfer target, or person")))
        .andExpect(
            content().string(containsString("BankAaa-EUR leg reconciled — statement 2026-05")));
  }
}
