package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): subdivision end to end against real Postgres — a posted-to leaf
 * becomes a parent, its postings move onto the catch-all, and the leaf's own account row is
 * untouched (name, type, currency) except for gaining children (data-model §5).
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SubdivisionServiceIntegrationTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";
  private static final String UNCATEGORIZED = "Uncategorized";

  @Autowired JdbcClient jdbcClient;
  @Autowired SubdivisionService subdivisionService;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private long cashEur;
  private long foodEur;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    cashEur = insertAccount("Cash", "asset");
    foodEur = insertAccount(FOOD, EXPENSE);
  }

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, :c) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", EUR)
        .query(Long.class)
        .single();
  }

  private long insertTransactionWithPosting(long accountId, long counterAccountId, String amount) {
    long txnId =
        jdbcClient
            .sql("insert into transaction (date) values ('2026-07-01') returning transaction_id")
            .query(Long.class)
            .single();
    insertPosting(txnId, accountId, amount);
    insertPosting(txnId, counterAccountId, new BigDecimal(amount).negate().toPlainString());
    return txnId;
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  @Test
  void subdividingPostedLeafMovesPostingsToCatchAll() {
    insertTransactionWithPosting(foodEur, cashEur, "5.00");

    SubdivisionResult result = subdivisionService.subdivideAccount(foodEur, MILK, UNCATEGORIZED);

    assertThat(result.child().name()).isEqualTo(MILK);
    assertThat(result.child().parentId()).isEqualTo(foodEur);
    assertThat(result.catchAll()).isNotNull();
    assertThat(result.catchAll().name()).isEqualTo(UNCATEGORIZED);
    assertThat(result.catchAll().parentId()).isEqualTo(foodEur);

    // The leaf's own postings moved to the catch-all; the leaf itself now has none.
    assertThat(accountService.hasPostings(foodEur)).isFalse();
    assertThat(accountService.hasPostings(result.catchAll().accountId())).isTrue();

    BigDecimal catchAllBalance =
        jdbcClient
            .sql("select sum(amount) from posting where account_id = :id")
            .param("id", result.catchAll().accountId())
            .query(BigDecimal.class)
            .single();
    assertThat(catchAllBalance).isEqualByComparingTo("5.00");

    // The now-parent Food account is unchanged apart from gaining children.
    Account foodAfter = accountService.findById(foodEur).orElseThrow();
    assertThat(foodAfter.name()).isEqualTo(FOOD);
    assertThat(foodAfter.type()).isEqualTo(EXPENSE);
    assertThat(foodAfter.currencyCode()).isEqualTo(EUR);
    List<Account> children = accountService.findChildrenOf(foodEur);
    assertThat(children).extracting(Account::name).containsExactly(MILK, UNCATEGORIZED);
  }

  @Test
  void subdividingAnUnpostedLeafSkipsTheCatchAll() {
    SubdivisionResult result = subdivisionService.subdivideAccount(foodEur, MILK, UNCATEGORIZED);

    assertThat(result.catchAll()).isNull();
    assertThat(accountService.findChildrenOf(foodEur))
        .extracting(Account::name)
        .containsExactly(MILK);
  }
}
