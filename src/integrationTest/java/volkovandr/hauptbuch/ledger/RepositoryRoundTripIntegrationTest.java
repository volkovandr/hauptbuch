package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.AccountRepository;
import volkovandr.hauptbuch.ledger.repository.ExchangeRateRepository;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Integration tier (plan §1.5): the repositories map rows ↔ records correctly against real
 * Postgres. This is the "repositories map rows ↔ records" half of the integration tier's remit —
 * distinct from the SQL-logic suite, which tests logic that lives in the SQL itself.
 *
 * <p>{@code @Transactional} rolls each test back, so the reused container (plan §15) stays clean
 * across tests and classes — important here because {@code settings} is a single shared row.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RepositoryRoundTripIntegrationTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String OPENING_BALANCES = "Opening Balances";

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountRepository accountRepository;
  @Autowired ExchangeRateRepository exchangeRateRepository;
  @Autowired TransactionRepository transactionRepository;
  @Autowired PayeeRepository payeeRepository;
  @Autowired SettingsRepository settingsRepository;

  /** A real cash account in the given currency, returning its id. */
  private long insertCashAccount(String currencyCode) {
    return jdbcClient
        .sql(
            """
            insert into account (name, type, currency_code) values ('Cash', 'asset', :c)
            returning account_id
            """)
        .param("c", currencyCode)
        .query(Long.class)
        .single();
  }

  @Test
  void accountRoundTripsIncludingTheSeededSystemLeaf() {
    Optional<Account> openingBalancesEur =
        accountRepository.findLeafUnderParentNamed(OPENING_BALANCES, EUR);
    assertThat(openingBalancesEur).isPresent();
    assertThat(openingBalancesEur.get().type()).isEqualTo("equity");
    assertThat(openingBalancesEur.get().currencyCode()).isEqualTo(EUR);

    Optional<Account> byId = accountRepository.findById(openingBalancesEur.get().accountId());
    assertThat(byId).contains(openingBalancesEur.get());
  }

  @Test
  void parentAccountIdsAreTheNonLeafAccounts() {
    // The two seeded system parents (Opening Balances, FX gain/loss) are parents of their leaves.
    List<Long> parentIds = accountRepository.findParentAccountIds();
    List<Long> systemParentIds =
        jdbcClient
            .sql(
                "select account_id from account "
                    + "where name in ('Opening Balances', 'FX gain/loss') and parent_id is null")
            .query(Long.class)
            .list();
    assertThat(parentIds).containsAll(systemParentIds);
  }

  @Test
  void exchangeRateCarriesForwardToTheMostRecentOnOrBeforeTheDate() {
    exchangeRateRepository.insert(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 1, 1), new BigDecimal("0.90000000"), "manual"));
    exchangeRateRepository.insert(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 3, 1), new BigDecimal("0.95000000"), "manual"));

    // On a gap date, the most recent prior rate carries forward.
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 2, 15)).orElseThrow())
        .isEqualByComparingTo("0.90");
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 3, 1)).orElseThrow())
        .isEqualByComparingTo("0.95");
    // Before any stored rate: empty.
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2025, 12, 31))).isEmpty();
  }

  @Test
  void transactionAndPostingsRoundTrip() {
    long cash = insertCashAccount(EUR);
    Account food = accountRepository.findLeafUnderParentNamed(OPENING_BALANCES, EUR).orElseThrow();

    long payeeId = payeeRepository.insert("Rewe");
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null,
                LocalDate.of(2026, 6, 1),
                payeeId,
                "groceries",
                "confirmed",
                null,
                null,
                null));

    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("-30.0000"), null, "unreconciled", null));
    transactionRepository.insertPosting(
        new Posting(
            null, txnId, food.accountId(), new BigDecimal("30.0000"), null, "unreconciled", null));

    Transaction loaded = transactionRepository.findById(txnId).orElseThrow();
    assertThat(loaded.date()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(loaded.payeeId()).isEqualTo(payeeId);
    assertThat(loaded.lifecycle()).isEqualTo("confirmed");
    assertThat(loaded.deletedAt()).isNull();
    assertThat(loaded.createdAt()).isNotNull();

    List<Posting> postings = transactionRepository.findPostings(txnId);
    assertThat(postings).hasSize(2);
    assertThat(postings)
        .extracting(p -> p.amount().intValueExact())
        .containsExactlyInAnyOrder(-30, 30);
  }

  @Test
  void softDeleteStampsDeletedAtOnceAndIsIdempotentThereafter() {
    long cash = insertCashAccount(EUR);
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null, LocalDate.of(2026, 6, 2), null, null, "confirmed", null, null, null));
    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("0.0000"), null, "unreconciled", null));

    assertThat(transactionRepository.softDelete(txnId)).isEqualTo(1);
    assertThat(transactionRepository.findById(txnId).orElseThrow().deletedAt()).isNotNull();
    // Already deleted: the guarded update affects no rows.
    assertThat(transactionRepository.softDelete(txnId)).isZero();
  }

  @Test
  void settingsRowExistsAndDisplayNameUpdates() {
    settingsRepository.updateDisplayName("Andrey");
    assertThat(settingsRepository.load().displayName()).isEqualTo("Andrey");
  }
}
