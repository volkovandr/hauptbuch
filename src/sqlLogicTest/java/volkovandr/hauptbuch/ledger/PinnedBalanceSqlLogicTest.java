package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.PinnedBalance;
import volkovandr.hauptbuch.ledger.repository.PinnedBalanceRepository;

/**
 * SQL-logic tier (plan §1.5): {@link PinnedBalanceRepository#findPinnedBalances()} — the landing
 * page's Balances panel query (issue landing-page/01). The logic lives in the SQL: a grouped sum of
 * postings per account across three tables, a left join so a pinned account with no posting still
 * yields a zero, a {@code filter} for the live-transaction scope, and the pinned/open/live
 * predicate. Tested with crafted books rather than as a row-mapping round-trip (CLAUDE.md §6).
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PinnedBalanceSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired PinnedBalanceRepository pinnedBalanceRepository;

  /**
   * Insert an asset account; {@code pinned}/{@code closed}/{@code deleted} toggle the predicate.
   */
  private long insertAccount(
      String name, String currency, boolean pinned, boolean closed, boolean deleted) {
    return jdbcClient
        .sql(
            """
            insert into account
              (name, type, currency_code, show_on_main_page, closed_at, deleted_at)
            values
              (:n, 'asset', :c, :pinned,
               case when :closed then date '2026-01-01' end,
               case when :deleted then now() end)
            returning account_id
            """)
        .param("n", name)
        .param("c", currency)
        .param("pinned", pinned)
        .param("closed", closed)
        .param("deleted", deleted)
        .query(Long.class)
        .single();
  }

  private long insertTransaction(LocalDate date, boolean deleted) {
    return jdbcClient
        .sql(
            """
            insert into transaction (date, lifecycle, deleted_at)
            values (:d, 'confirmed', case when :deleted then now() end)
            returning transaction_id
            """)
        .param("d", date)
        .param("deleted", deleted)
        .query(Long.class)
        .single();
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql(
            """
            insert into posting (transaction_id, account_id, amount, reconciliation)
            values (:t, :a, :amt, 'unreconciled')
            """)
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private void post(long accountId, LocalDate date, String amount) {
    insertPosting(insertTransaction(date, false), accountId, amount);
  }

  @Test
  void sumsLivePostingsPerPinnedAccountAcrossCurrenciesWithNoDateFilter() {
    long giro = insertAccount("Giro", "EUR", true, false, false);
    long sparkonto = insertAccount("Sparkonto", "CHF", true, false, false);

    post(giro, LocalDate.of(2020, 1, 1), "1000.00"); // an opening-balance-era posting
    post(giro, LocalDate.now().plusYears(5), "234.56"); // a future-dated posting still counts
    post(sparkonto, LocalDate.now(), "10000.00");

    List<PinnedBalance> balances = pinnedBalanceRepository.findPinnedBalances();

    assertThat(balances).extracting(PinnedBalance::name).containsExactly("Giro", "Sparkonto");
    assertThat(byName(balances, "Giro").balance()).isEqualByComparingTo("1234.56");
    assertThat(byName(balances, "Giro").currencyCode()).isEqualTo("EUR");
    assertThat(byName(balances, "Sparkonto").balance()).isEqualByComparingTo("10000.00");
  }

  @Test
  void pinnedAccountWithNoLivePostingStillShowsZeroBalance() {
    insertAccount("Fresh", "EUR", true, false, false);
    long voided = insertAccount("Voided", "EUR", true, false, false);
    insertPosting(insertTransaction(LocalDate.now(), true), voided, "99.00");

    List<PinnedBalance> balances = pinnedBalanceRepository.findPinnedBalances();

    assertThat(balances).extracting(PinnedBalance::name).containsExactly("Fresh", "Voided");
    assertThat(byName(balances, "Fresh").balance()).isEqualByComparingTo("0");
    assertThat(byName(balances, "Voided").balance()).isEqualByComparingTo("0");
  }

  @Test
  void liveTransactionScopeExcludesSoftDeletedTransactionsFromTheSum() {
    long giro = insertAccount("Giro", "EUR", true, false, false);
    post(giro, LocalDate.now(), "40.00");
    insertPosting(insertTransaction(LocalDate.now(), true), giro, "1000.00");

    assertThat(byName(pinnedBalanceRepository.findPinnedBalances(), "Giro").balance())
        .isEqualByComparingTo("40.00");
  }

  @Test
  void closedDeletedAndUnpinnedAccountsAreNotListed() {
    insertAccount("Pinned Open", "EUR", true, false, false);
    insertAccount("Pinned Closed", "EUR", true, true, false);
    insertAccount("Pinned Deleted", "EUR", true, false, true);
    insertAccount("Unpinned", "EUR", false, false, false);

    assertThat(pinnedBalanceRepository.findPinnedBalances())
        .extracting(PinnedBalance::name)
        .containsExactly("Pinned Open");
  }

  @Test
  void rowsAreOrderedAlphabeticallyByName() {
    insertAccount("Zins", "EUR", true, false, false);
    insertAccount("Anna", "EUR", true, false, false);
    insertAccount("Bar", "EUR", true, false, false);

    assertThat(pinnedBalanceRepository.findPinnedBalances())
        .extracting(PinnedBalance::name)
        .containsExactly("Anna", "Bar", "Zins");
  }

  private static PinnedBalance byName(List<PinnedBalance> balances, String name) {
    return balances.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
  }
}
