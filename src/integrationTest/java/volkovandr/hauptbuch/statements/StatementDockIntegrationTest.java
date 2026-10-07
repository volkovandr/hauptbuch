package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
 * Integration tier (CLAUDE.md §6): the dock on the statement page (slice d1) through MockMvc
 * against real Postgres — a missing line opens the dock pre-filled, and Save books the transaction
 * and matches it reconciled in one go, or books and matches nothing when it is refused. Each test
 * is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementDockIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long foodId;
  private long statementId;
  private long shopLine;
  private long salaryLine;

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
    long profile = StatementFixtures.saveProfile(mockMvc, jdbcClient);
    statementId = StatementFixtures.uploadAndCreate(mockMvc, profile, accountId);
    shopLine = lineWithAmount("-12.50");
    salaryLine = lineWithAmount("1234.56");
  }

  private long lineWithAmount(String amount) {
    return jdbcClient
        .sql("select statement_line_id from statement_line where statement_id = :s and amount = :a")
        .param("s", statementId)
        .param("a", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private String url(long lineId, String action) {
    return "/statements/" + statementId + "/lines/" + lineId + "/" + action;
  }

  private long count(String sql) {
    return jdbcClient.sql(sql).query(Long.class).single();
  }

  @Test
  void missingLineOffersCreateAndOpensTheDockPrefilled() throws Exception {
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("dock=" + shopLine)))
        .andExpect(content().string(not(containsString("Save and match"))));

    mockMvc
        .perform(get("/statements/" + statementId).param("dock", String.valueOf(shopLine)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Save and match")))
        .andExpect(content().string(containsString("value=\"2026-05-02\"")))
        .andExpect(content().string(containsString("value=\"BankAaa-EUR\"")))
        .andExpect(content().string(containsString("value=\"-12,50\"")))
        .andExpect(content().string(containsString("Bank category:")))
        .andExpect(content().string(containsString("Groceries")))
        .andExpect(content().string(containsString("hx-post=\"/categories/resolve\"")));
  }

  @Test
  void payeeInTheBankTextAndItsLastCategoryArePrefilled() throws Exception {
    long payeeId =
        jdbcClient
            .sql("insert into payee (name) values ('ShopAaa') returning payee_id")
            .query(Long.class)
            .single();
    long earlier =
        jdbcClient
            .sql(
                "insert into transaction (date, payee_id, lifecycle) values ('2026-04-01', :p,"
                    + " 'confirmed') returning transaction_id")
            .param("p", payeeId)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount) values (:t, :a, -5), (:t,"
                + " :c, 5)")
        .param("t", earlier)
        .param("a", accountId)
        .param("c", foodId)
        .update();

    mockMvc
        .perform(get("/statements/" + statementId).param("dock", String.valueOf(shopLine)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"payeeText\"")))
        .andExpect(content().string(containsString("value=\"ShopAaa\"")))
        .andExpect(content().string(containsString("name=\"categoryId\" value=\"" + foodId)));
  }

  @Test
  void saveBooksTheTransactionAndMatchesItReconciled() throws Exception {
    mockMvc
        .perform(
            post(url(shopLine, "create"))
                .param("date", "2026-05-02")
                .param("payeeText", "ShopAaa - Berlin")
                .param("categoryId", String.valueOf(foodId))
                .param("note", "weekly shop"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attribute("notice", "Transaction created and matched."));

    BigDecimal legAmount =
        jdbcClient
            .sql(
                "select p.amount from statement_match m join posting p on p.posting_id ="
                    + " m.posting_id where m.statement_line_id = :l and p.account_id = :a")
            .param("l", shopLine)
            .param("a", accountId)
            .query(BigDecimal.class)
            .single();
    assertThat(legAmount).isEqualByComparingTo("-12.50");
    assertThat(
            count(
                "select count(*) from posting p join statement_match m on m.posting_id ="
                    + " p.posting_id where p.reconciliation = 'reconciled'"))
        .isEqualTo(1);
  }

  @Test
  void bankSignWinsOverTheCategoryDirectionSoAPositiveLineOnAnExpenseIsARefund() throws Exception {
    mockMvc
        .perform(
            post(url(salaryLine, "create"))
                .param("date", "2026-05-05")
                .param("categoryId", String.valueOf(foodId)))
        .andExpect(flash().attribute("notice", "Transaction created and matched."));

    BigDecimal foodLeg =
        jdbcClient
            .sql("select amount from posting where account_id in (select account_id from account"
                    + " where parent_id = :f or account_id = :f)")
            .param("f", foodId)
            .query(BigDecimal.class)
            .single();
    assertThat(foodLeg).isEqualByComparingTo("-1234.56");
  }

  @Test
  void saveWithoutACategoryBooksNothingAndReopensTheDock() throws Exception {
    mockMvc
        .perform(post(url(shopLine, "create")).param("date", "2026-05-02"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/" + statementId + "?dock=" + shopLine))
        .andExpect(flash().attributeExists("error"));

    assertThat(count("select count(*) from transaction")).isZero();
    assertThat(count("select count(*) from statement_match")).isZero();
  }

  @Test
  void lineThatIsAlreadyMatchedCannotBeCreatedAgain() throws Exception {
    mockMvc.perform(
        post(url(shopLine, "create"))
            .param("date", "2026-05-02")
            .param("categoryId", String.valueOf(foodId)));

    mockMvc
        .perform(
            post(url(shopLine, "create"))
                .param("date", "2026-05-02")
                .param("categoryId", String.valueOf(foodId)))
        .andExpect(flash().attributeExists("error"));

    assertThat(count("select count(*) from transaction")).isEqualTo(1);
    assertThat(count("select count(*) from statement_match")).isEqualTo(1);
  }
}
