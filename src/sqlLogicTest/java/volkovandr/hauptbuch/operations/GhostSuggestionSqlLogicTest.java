package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * SQL-logic tier (plan §1.5): the dock's ghost category query (register §3.9) — most-common
 * category per payee, ties broken by most recent use (Q-UI-3), rolling per-currency leaves up to
 * their semantic parent (§6.5). Grouping + a two-key tie-break over a multi-table join is
 * SQL-resident logic, so it is tested here with crafted books.
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw JDBC only seeds.
 * {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GhostSuggestionSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";

  @Autowired JdbcClient jdbcClient;
  @Autowired GhostSuggestionRepository ghostSuggestionRepository;

  private long insertAccount(String name, String type, String currency, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) values (:n, :t, :c, :p) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  /** An auto-managed currency leaf (data-model §6.5) — named after the bare currency code. */
  private long insertCurrencyLeaf(String currency, String type, long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id, currency_leaf) "
                + "values (:n, :t, :c, :p, true) "
                + "returning account_id")
        .param("n", currency)
        .param("t", type)
        .param("c", currency)
        .param("p", parentId)
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

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  /** A payee spend: cash out, category in. */
  private void spend(String date, long payeeId, long cash, long category, String magnitude) {
    long txn = insertTxn(date, payeeId);
    insertPosting(txn, cash, "-" + magnitude);
    insertPosting(txn, category, magnitude);
  }

  @Test
  void suggestsThePayeesMostFrequentCategory() {
    long cash = insertAccount("Cash", ASSET, EUR, null);
    long fuel = insertAccount("Fuel", EXPENSE, EUR, null);
    long snacks = insertAccount("Snacks", EXPENSE, EUR, null);
    long station = insertPayee("Shell");

    // Fuel three times, Snacks once — the mode is Fuel (register §3.9).
    spend("2026-01-01", station, cash, fuel, "50");
    spend("2026-01-08", station, cash, fuel, "55");
    spend("2026-01-15", station, cash, snacks, "3");
    spend("2026-01-22", station, cash, fuel, "48");

    Optional<GhostSuggestion> suggestion = ghostSuggestionRepository.suggestFor(station);
    assertThat(suggestion).isPresent();
    assertThat(suggestion.get().categoryId()).isEqualTo(fuel);
    assertThat(suggestion.get().categoryName()).isEqualTo("Fuel");
  }

  @Test
  void breaksFrequencyTieByMostRecentUse() {
    long cash = insertAccount("Cash", ASSET, EUR, null);
    long food = insertAccount("Food", EXPENSE, EUR, null);
    long drinks = insertAccount("Drinks", EXPENSE, EUR, null);
    long payee = insertPayee("Kiosk");

    // One each — a tie; Drinks was used more recently, so it wins (Q-UI-3).
    spend("2026-02-01", payee, cash, food, "10");
    spend("2026-02-10", payee, cash, drinks, "4");

    assertThat(ghostSuggestionRepository.suggestFor(payee))
        .get()
        .extracting(GhostSuggestion::categoryId)
        .isEqualTo(drinks);
  }

  @Test
  void rollsPerCurrencyLeavesUpToTheirSemanticParent() {
    long cash = insertAccount("Cash", ASSET, EUR, null);
    long chfCard = insertAccount("CHF Card", ASSET, CHF, null);
    // Food is subdivided into per-currency leaves (§6.5).
    long food = insertAccount("Food", EXPENSE, EUR, null);
    long foodEur = insertCurrencyLeaf(EUR, EXPENSE, food);
    long foodChf = insertCurrencyLeaf(CHF, EXPENSE, food);
    long other = insertAccount("Other", EXPENSE, EUR, null);
    long payee = insertPayee("Migros");

    // Two Food spends across two currency leaves + one Other. Rolled up, Food (2) beats Other (1),
    // even though neither Food leaf alone reaches 2.
    spend("2026-03-01", payee, cash, foodEur, "10");
    spend("2026-03-05", payee, chfCard, foodChf, "12");
    spend("2026-03-09", payee, cash, other, "5");

    GhostSuggestion suggestion = ghostSuggestionRepository.suggestFor(payee).orElseThrow();
    assertThat(suggestion.categoryId()).isEqualTo(food);
    assertThat(suggestion.categoryName()).isEqualTo("Food");
  }

  @Test
  void keepsSemanticChildLeafInsteadOfRollingUpToTheParent() {
    long cash = insertAccount("Cash", ASSET, EUR, null);
    // Food has real sub-categories (semantic children, not per-currency variants). A posting lands
    // on the "Sweets" leaf; the suggestion must be "Sweets", not its parent "Food" (Food cannot be
    // posted to, so rolling up to it is useless — ui-issue-list).
    long food = insertAccount("Food", EXPENSE, EUR, null);
    long sweets = insertAccount("Sweets", EXPENSE, EUR, food);
    insertAccount("Non-Sweets", EXPENSE, EUR, food);
    long payee = insertPayee("Confiserie");

    spend("2026-05-01", payee, cash, sweets, "6");
    spend("2026-05-08", payee, cash, sweets, "7");

    GhostSuggestion suggestion = ghostSuggestionRepository.suggestFor(payee).orElseThrow();
    assertThat(suggestion.categoryId()).isEqualTo(sweets);
    assertThat(suggestion.categoryName()).isEqualTo("Sweets");
  }

  @Test
  void ignoresVoidedTransactions() {
    long cash = insertAccount("Cash", ASSET, EUR, null);
    long fuel = insertAccount("Fuel", EXPENSE, EUR, null);
    long payee = insertPayee("Shell");

    long txn = insertTxn("2026-04-01", payee);
    insertPosting(txn, cash, "-50");
    insertPosting(txn, fuel, "50");
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :t")
        .param("t", txn)
        .update();

    // The only transaction is voided → no suggestion.
    assertThat(ghostSuggestionRepository.suggestFor(payee)).isEmpty();
  }

  @Test
  void returnsEmptyForPayeeWithNoTransactions() {
    long payee = insertPayee("Brand New");
    assertThat(ghostSuggestionRepository.suggestFor(payee)).isEmpty();
  }
}
