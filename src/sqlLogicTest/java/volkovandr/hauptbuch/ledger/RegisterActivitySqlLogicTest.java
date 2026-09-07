package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * SQL-logic tier (plan §1.5): {@link RegisterRepository#findAccountIdsWithActivity} — the "Last
 * used" picker's membership (issue transaction-register-ui/22). It joins {@code posting}, {@code
 * transaction} and {@code account}, filters on the live transaction's date, and returns the
 * distinct own-account ids that have a posting in the window — logic that lives in the SQL, so it
 * is tested here rather than as a row-mapping round-trip (CLAUDE.md §6).
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds crafted books. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterActivitySqlLogicTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final LocalDate JAN_1 = LocalDate.parse("2026-01-01");
  private static final LocalDate JAN_31 = LocalDate.parse("2026-01-31");

  @Autowired JdbcClient jdbcClient;
  @Autowired RegisterRepository registerRepository;

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

  private long insertPersonLeaf(String name) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, person_leaf) "
                + "values (:n, 'asset', :c, true) returning account_id")
        .param("n", name)
        .param("c", EUR)
        .query(Long.class)
        .single();
  }

  private long insertTxn(String date) {
    return jdbcClient
        .sql("insert into transaction (date) values (:d) returning transaction_id")
        .param("d", LocalDate.parse(date))
        .query(Long.class)
        .single();
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private void softDeleteTxn(long txnId) {
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :t")
        .param("t", txnId)
        .update();
  }

  /** A one-account/one-category posting pair on {@code date}. */
  private void spend(String date, long fromAccount, long category, String magnitude) {
    long txn = insertTxn(date);
    insertPosting(txn, fromAccount, "-" + magnitude);
    insertPosting(txn, category, magnitude);
  }

  @Test
  void returnsOnlyAccountsWithActivityInsideTheRange() {
    long cash = insertAccount("Cash", ASSET);
    long giro = insertAccount("Giro", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2026-01-10", cash, food, "20.00"); // in range
    spend("2026-02-10", giro, food, "30.00"); // after range

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31))
        .containsExactly(cash)
        .doesNotContain(giro);
  }

  @Test
  void countsBackdatedPostingByTransactionDateNotInsertOrder() {
    long cash = insertAccount("Cash", ASSET);
    long giro = insertAccount("Giro", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2026-03-01", cash, food, "10.00"); // inserted first, dated later — out of range
    spend("2026-01-15", giro, food, "10.00"); // inserted after, dated earlier — in range

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31)).containsExactly(giro);
  }

  @Test
  void includesPersonDebtLeaves() {
    // A person-funded transaction's only own leg can be the person's debt leaf; excluding it here
    // would make such a transaction produce no register row under the default view (plan 8b.1).
    long personLeaf = insertPersonLeaf("personal.EUR");
    long food = insertAccount("Food", EXPENSE);
    spend("2026-01-12", personLeaf, food, "15.00");

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31))
        .containsExactly(personLeaf);
  }

  @Test
  void excludesCategoryAndOtherNonOwnLegs() {
    long cash = insertAccount("Cash", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2026-01-12", cash, food, "15.00");

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31))
        .containsExactly(cash)
        .doesNotContain(food);
  }

  @Test
  void excludesLegsOfVoidedTransactions() {
    long cash = insertAccount("Cash", ASSET);
    long food = insertAccount("Food", EXPENSE);
    long txn = insertTxn("2026-01-12");
    insertPosting(txn, cash, "-15.00");
    insertPosting(txn, food, "15.00");
    softDeleteTxn(txn);

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31)).isEmpty();
  }

  @Test
  void excludesSoftDeletedAccounts() {
    long cash = insertAccount("Cash", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2026-01-12", cash, food, "15.00");
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", cash)
        .update();

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31)).isEmpty();
  }

  @Test
  void nullBoundsMeanUnbounded() {
    long cash = insertAccount("Cash", ASSET);
    long giro = insertAccount("Giro", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2020-01-01", cash, food, "5.00");
    spend("2030-12-31", giro, food, "5.00");

    assertThat(registerRepository.findAccountIdsWithActivity(null, null)).contains(cash, giro);
  }

  @Test
  void deduplicatesLeafWithSeveralPostingsInTheRange() {
    long cash = insertAccount("Cash", ASSET);
    long food = insertAccount("Food", EXPENSE);
    spend("2026-01-05", cash, food, "5.00");
    spend("2026-01-06", cash, food, "6.00");

    assertThat(registerRepository.findAccountIdsWithActivity(JAN_1, JAN_31)).containsExactly(cash);
  }
}
