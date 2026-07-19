package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;

/**
 * SQL-logic tier (plan §1.5): {@link AccountOwnerRepository#findAllPersonCurrencyBalances()} and
 * {@link AccountOwnerRepository#findPersonCurrencyBalances(Long)} — the per-person per-currency
 * signed-balance queries (data-model §7).
 *
 * <p>The logic lives in SQL: a sum of postings per person per currency, scoped to live transactions
 * and postings. These queries are crafted for the People page and settle-up functionality, so they
 * are tested with real posting data rather than as simple row-mapping round-trips.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersonCurrencyBalanceSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountOwnerRepository accountOwnerRepository;

  /** Helper: insert a person and return the ID. */
  private long insertPerson(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:n) returning person_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  /** Helper: insert an asset account and return the ID. */
  private long insertAssetAccount(String name, String currencyCode) {
    return jdbcClient
        .sql(
            """
            insert into account (name, type, currency_code)
            values (:n, 'asset', :cc) returning account_id
            """)
        .param("n", name)
        .param("cc", currencyCode)
        .query(Long.class)
        .single();
  }

  /** Helper: link an account to a person. */
  private void linkAccountToPerson(long accountId, long personId) {
    jdbcClient
        .sql(
            """
            insert into account_owner (account_id, person_id)
            values (:aid, :pid)
            """)
        .param("aid", accountId)
        .param("pid", personId)
        .update();
  }

  /** Helper: insert a simple transaction and posting. */
  private void insertPosting(long accountId, BigDecimal amount) {
    long txnId =
        jdbcClient
            .sql(
                """
                insert into transaction (date, lifecycle)
                values (now(), 'confirmed') returning transaction_id
                """)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            """
            insert into posting
            (transaction_id, account_id, amount, reconciliation)
            values (:txn, :acc, :amt, 'unreconciled')
            """)
        .param("txn", txnId)
        .param("acc", accountId)
        .param("amt", amount)
        .update();
  }

  @Test
  void returnsZeroForPersonWithNoPostings() {
    long personId = insertPerson("AliceSlt");
    long accountId = insertAssetAccount("personal.EUR", "EUR");
    linkAccountToPerson(accountId, personId);

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    assertThat(balances).isEmpty();
  }

  @Test
  void sumsSingleCurrencyPostings() {
    long personId = insertPerson("AliceSlt");
    long accountId = insertAssetAccount("personal.EUR", "EUR");
    linkAccountToPerson(accountId, personId);

    insertPosting(accountId, new BigDecimal("10.00"));
    insertPosting(accountId, new BigDecimal("5.00"));

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    assertThat(balances).hasSize(1);
    assertThat(balances.get(0).getSignedBalance()).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(balances.get(0).getCurrencyCode()).isEqualTo("EUR");
  }

  @Test
  void sumsPerCurrency() {
    long personId = insertPerson("AliceSlt");
    long eurAccountId = insertAssetAccount("personal.EUR", "EUR");
    long chfAccountId = insertAssetAccount("personal.CHF", "CHF");
    linkAccountToPerson(eurAccountId, personId);
    linkAccountToPerson(chfAccountId, personId);

    insertPosting(eurAccountId, new BigDecimal("10.00"));
    insertPosting(chfAccountId, new BigDecimal("20.00"));

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    assertThat(balances).hasSize(2);
    java.util.Map<String, BigDecimal> byCode =
        balances.stream()
            .collect(
                Collectors.toMap(
                    AccountOwnerRepository.PersonCurrencyBalance::getCurrencyCode,
                    AccountOwnerRepository.PersonCurrencyBalance::getSignedBalance));
    assertThat(byCode.get("EUR")).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(byCode.get("CHF")).isEqualByComparingTo(new BigDecimal("20.00"));
  }

  @Test
  void scopsToLiveTransactions() {
    long personId = insertPerson("AliceSlt");
    long accountId = insertAssetAccount("personal.EUR", "EUR");
    linkAccountToPerson(accountId, personId);

    insertPosting(accountId, new BigDecimal("10.00"));

    // Insert a posting on a deleted transaction.
    long txnId =
        jdbcClient
            .sql(
                """
                insert into transaction (date, lifecycle, deleted_at)
                values (now(), 'confirmed', now()) returning transaction_id
                """)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            """
            insert into posting (transaction_id, account_id, amount, reconciliation)
            values (:txn, :acc, :amt, 'unreconciled')
            """)
        .param("txn", txnId)
        .param("acc", accountId)
        .param("amt", new BigDecimal("100.00"))
        .update();

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    // Only the live posting (10.00) is counted; the deleted transaction's posting is excluded.
    assertThat(balances).hasSize(1);
    assertThat(balances.get(0).getSignedBalance()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  @Test
  void handlesNegativeBalances() {
    long personId = insertPerson("AliceSlt");
    long accountId = insertAssetAccount("personal.EUR", "EUR");
    linkAccountToPerson(accountId, personId);

    insertPosting(accountId, new BigDecimal("-15.00"));
    insertPosting(accountId, new BigDecimal("5.00"));

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    assertThat(balances).hasSize(1);
    assertThat(balances.get(0).getSignedBalance()).isEqualByComparingTo(new BigDecimal("-10.00"));
  }

  @Test
  void findAllPersonCurrencyBalancesReturnsAllPersons() {
    long alice = insertPerson("AliceSlt");
    long bob = insertPerson("BobSlt");

    long aliceEurId = insertAssetAccount("personal.EUR", "EUR");
    long bobChfId = insertAssetAccount("personal.CHF", "CHF");
    linkAccountToPerson(aliceEurId, alice);
    linkAccountToPerson(bobChfId, bob);

    insertPosting(aliceEurId, new BigDecimal("10.00"));
    insertPosting(bobChfId, new BigDecimal("20.00"));

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findAllPersonCurrencyBalances();

    assertThat(balances).hasSize(2);
    java.util.Map<Long, java.util.Map<String, BigDecimal>> byPersonAndCode =
        balances.stream()
            .collect(
                Collectors.groupingBy(
                    AccountOwnerRepository.PersonCurrencyBalance::getPersonId,
                    Collectors.toMap(
                        AccountOwnerRepository.PersonCurrencyBalance::getCurrencyCode,
                        AccountOwnerRepository.PersonCurrencyBalance::getSignedBalance)));

    assertThat(byPersonAndCode.get(alice).get("EUR")).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(byPersonAndCode.get(bob).get("CHF")).isEqualByComparingTo(new BigDecimal("20.00"));
  }

  @Test
  void excludesDeletedPersons() {
    long alice = insertPerson("AliceSlt");
    long bob = insertPerson("BobSlt");

    long aliceId = insertAssetAccount("personal.EUR", "EUR");
    long bobId = insertAssetAccount("personal.EUR", "EUR");
    linkAccountToPerson(aliceId, alice);
    linkAccountToPerson(bobId, bob);

    insertPosting(aliceId, new BigDecimal("10.00"));
    insertPosting(bobId, new BigDecimal("20.00"));

    // Soft-delete Bob.
    jdbcClient
        .sql("update person set deleted_at = now() where person_id = :id")
        .param("id", bob)
        .update();

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findAllPersonCurrencyBalances();

    // Only Alice's balance is returned.
    assertThat(balances).hasSize(1);
    assertThat(balances.get(0).getPersonId()).isEqualTo(alice);
  }
}
