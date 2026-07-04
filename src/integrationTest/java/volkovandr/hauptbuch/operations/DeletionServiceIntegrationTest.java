package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): category deletion end to end against real Postgres. Covers a
 * childless leaf (simple reassign-then-delete), a parent with children (the whole subtree's
 * postings land on the target and every subtree row is gone), and the rejected
 * target-inside-subtree case (data-model §5).
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DeletionServiceIntegrationTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";
  private static final String SWEETS = "Sweets";
  private static final String MMS = "M&Ms";
  private static final String GROCERIES = "Groceries";

  @Autowired JdbcClient jdbcClient;
  @Autowired DeletionService deletionService;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private long cash;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    cash = insertAccount("Cash", "asset", null);
  }

  private long insertAccount(String name, String type, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) "
                + "values (:n, :t, :c, :p) returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", EUR)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void post(long accountId, String amount) {
    long txnId =
        jdbcClient
            .sql("insert into transaction (date) values ('2026-07-01') returning transaction_id")
            .query(Long.class)
            .single();
    insertPosting(txnId, accountId, amount);
    insertPosting(txnId, cash, new BigDecimal(amount).negate().toPlainString());
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private BigDecimal balanceOf(long accountId) {
    return jdbcClient
        .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
        .param("id", accountId)
        .query(BigDecimal.class)
        .single();
  }

  private boolean isLive(long accountId) {
    return accountService.findById(accountId).map(a -> a.deletedAt() == null).orElse(false);
  }

  @Test
  void childlessLeafReassignsThenDeletes() {
    long food = insertAccount(FOOD, EXPENSE, null);
    long groceries = insertAccount(GROCERIES, EXPENSE, null);
    post(food, "5.00");

    deletionService.deleteCategory(food, groceries);

    assertThat(isLive(food)).isFalse();
    assertThat(isLive(groceries)).isTrue();
    assertThat(accountService.hasPostings(food)).isFalse();
    assertThat(balanceOf(groceries)).isEqualByComparingTo("5.00");
  }

  @Test
  void parentWithChildrenDeletesWholeSubtreeAndMovesEveryPosting() {
    long food = insertAccount(FOOD, EXPENSE, null);
    long milk = insertAccount(MILK, EXPENSE, food);
    long sweets = insertAccount(SWEETS, EXPENSE, food);
    long mms = insertAccount(MMS, EXPENSE, sweets);
    long groceries = insertAccount(GROCERIES, EXPENSE, null);
    post(milk, "3.00");
    post(mms, "2.00");

    deletionService.deleteCategory(food, groceries);

    // Every subtree row — parent, children, and grandchild — is gone.
    assertThat(isLive(food)).isFalse();
    assertThat(isLive(milk)).isFalse();
    assertThat(isLive(sweets)).isFalse();
    assertThat(isLive(mms)).isFalse();
    // Every descendant's postings landed on the target.
    assertThat(isLive(groceries)).isTrue();
    assertThat(balanceOf(groceries)).isEqualByComparingTo("5.00");
    assertThat(accountService.hasPostings(milk)).isFalse();
    assertThat(accountService.hasPostings(mms)).isFalse();
  }

  @Test
  void deletingAnOnlyChildLetsItsParentReceiveThePostings() {
    // Food → Sweets → M&Ms, with M&Ms the only child of Sweets. Deleting M&Ms should let Sweets —
    // which becomes a leaf once M&Ms is gone — receive the postings.
    long food = insertAccount(FOOD, EXPENSE, null);
    long sweets = insertAccount(SWEETS, EXPENSE, food);
    long mms = insertAccount(MMS, EXPENSE, sweets);
    post(mms, "2.00");

    deletionService.deleteCategory(mms, sweets);

    assertThat(isLive(mms)).isFalse();
    assertThat(isLive(sweets)).isTrue();
    assertThat(isLive(food)).isTrue();
    assertThat(accountService.hasPostings(mms)).isFalse();
    assertThat(balanceOf(sweets)).isEqualByComparingTo("2.00");
  }

  @Test
  void rejectsTargetInsideTheSubtree() {
    long food = insertAccount(FOOD, EXPENSE, null);
    long milk = insertAccount(MILK, EXPENSE, food);
    post(milk, "3.00");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(food, milk))
        .withMessageContaining("within the subtree");

    // Nothing deleted, no postings moved.
    assertThat(isLive(food)).isTrue();
    assertThat(isLive(milk)).isTrue();
    assertThat(balanceOf(milk)).isEqualByComparingTo("3.00");
  }
}
