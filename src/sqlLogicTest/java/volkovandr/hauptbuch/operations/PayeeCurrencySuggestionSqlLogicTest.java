package volkovandr.hauptbuch.operations;

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
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * SQL-logic tier (plan §1.5): the dock's currency pre-selection (issue transaction-register-ui/17)
 * — the transaction currency of the most recent live transaction a payee had on an account. A
 * latest-row pick plus an aggregate over the other legs' currencies is SQL-resident logic, so it is
 * tested here with crafted books.
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw JDBC only seeds.
 * {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PayeeCurrencySuggestionSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";

  @Autowired JdbcClient jdbcClient;
  @Autowired GhostSuggestionRepository ghostSuggestionRepository;

  private long insertAccount(String name, String type, String currency) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, :c) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .query(Long.class)
        .single();
  }

  private long insertPayee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private long insertTxn(String date, long payeeId) {
    return jdbcClient
        .sql(
            "insert into transaction (date, payee_id, lifecycle) values (:d, :p, 'confirmed') "
                + "returning transaction_id")
        .param("d", LocalDate.parse(date))
        .param("p", payeeId)
        .query(Long.class)
        .single();
  }

  private void insertPosting(long txnId, long accountId, String amount, String baseAmount) {
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, base_amount)"
                + " values (:t, :a, :amt, :base)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .param("base", baseAmount == null ? null : new BigDecimal(baseAmount))
        .update();
  }

  /** A single-currency spend: account out, category in. */
  private long spend(String date, long payeeId, long account, long category, String magnitude) {
    long txn = insertTxn(date, payeeId);
    insertPosting(txn, account, "-" + magnitude, null);
    insertPosting(txn, category, magnitude, null);
    return txn;
  }

  /** A cross-currency spend balanced in base (data-model §6.4): EUR account, foreign category. */
  private long crossSpend(String date, long payeeId, long eurAccount, long category) {
    long txn = insertTxn(date, payeeId);
    insertPosting(txn, eurAccount, "-9", "-9");
    insertPosting(txn, category, "10", "9");
    return txn;
  }

  @Test
  void singleCurrencyHistorySuggestsTheAccountsOwnCurrency() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long food = insertAccount("Food", EXPENSE, EUR);
    long shop = insertPayee("ShopAaa");
    spend("2026-01-05", shop, cash, food, "12");

    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).contains(EUR);
  }

  @Test
  void crossCurrencyHistorySuggestsTheOtherLegsCurrency() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long foodUsd = insertAccount("Food", EXPENSE, USD);
    long shop = insertPayee("ShopAaa");
    crossSpend("2026-01-05", shop, cash, foodUsd);

    // A EUR account paying a USD bill was a USD transaction.
    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).contains(USD);
  }

  @Test
  void mostRecentTransactionWinsOverOlderOnes() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long foodEur = insertAccount("Food", EXPENSE, EUR);
    long foodChf = insertAccount("Food CHF", EXPENSE, CHF);
    long shop = insertPayee("ShopAaa");
    // Inserted out of date order: the backdated EUR spend must not shadow the later CHF one.
    crossSpend("2026-03-01", shop, cash, foodChf);
    spend("2026-01-05", shop, cash, foodEur, "12");

    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).contains(CHF);
  }

  @Test
  void sameDayTieGoesToTheLaterEnteredTransaction() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long foodEur = insertAccount("Food", EXPENSE, EUR);
    long foodUsd = insertAccount("Food USD", EXPENSE, USD);
    long shop = insertPayee("ShopAaa");
    spend("2026-02-01", shop, cash, foodEur, "12");
    crossSpend("2026-02-01", shop, cash, foodUsd);

    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).contains(USD);
  }

  @Test
  void historyOnAnotherAccountDoesNotCount() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long card = insertAccount("BankAaa-EUR", ASSET, EUR);
    long foodUsd = insertAccount("Food USD", EXPENSE, USD);
    long shop = insertPayee("ShopAaa");
    crossSpend("2026-02-01", shop, card, foodUsd);

    // The pair (payee, account) is the key: Cash never paid ShopAaa.
    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).isEmpty();
  }

  @Test
  void ignoresVoidedTransactions() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long foodEur = insertAccount("Food", EXPENSE, EUR);
    long foodUsd = insertAccount("Food USD", EXPENSE, USD);
    long shop = insertPayee("ShopAaa");
    spend("2026-01-05", shop, cash, foodEur, "12");
    long voided = crossSpend("2026-02-01", shop, cash, foodUsd);
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :t")
        .param("t", voided)
        .update();

    // The later USD spend is voided, so the live EUR one is the most recent.
    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).contains(EUR);
  }

  @Test
  void returnsEmptyForPayeeWithNoTransactions() {
    long cash = insertAccount("Cash", ASSET, EUR);
    long shop = insertPayee("ShopBbb");

    assertThat(ghostSuggestionRepository.suggestCurrencyFor(shop, cash)).isEmpty();
  }
}
