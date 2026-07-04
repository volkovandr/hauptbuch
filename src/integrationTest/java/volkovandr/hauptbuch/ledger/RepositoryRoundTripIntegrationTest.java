package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;
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
  private static final String CONFIRMED = "confirmed";
  private static final String UNRECONCILED = "unreconciled";

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountService accountService;
  @Autowired TransactionRepository transactionRepository;
  @Autowired PayeeRepository payeeRepository;
  @Autowired SettingsRepository settingsRepository;
  @Autowired CurrencyRepository currencyRepository;
  @Autowired CurrencyService currencyService;

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
  void transactionAndPostingsRoundTrip() {
    long cash = insertCashAccount(EUR);
    Account food = accountService.findLeafUnderParentNamed(OPENING_BALANCES, EUR).orElseThrow();

    long payeeId = payeeRepository.insert("Rewe");
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null, LocalDate.of(2026, 6, 1), payeeId, "groceries", CONFIRMED, null, null, null));

    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("-30.0000"), null, UNRECONCILED, null));
    transactionRepository.insertPosting(
        new Posting(
            null, txnId, food.accountId(), new BigDecimal("30.0000"), null, UNRECONCILED, null));

    Transaction loaded = transactionRepository.findById(txnId).orElseThrow();
    assertThat(loaded.date()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(loaded.payeeId()).isEqualTo(payeeId);
    assertThat(loaded.lifecycle()).isEqualTo(CONFIRMED);
    assertThat(loaded.deletedAt()).isNull();
    assertThat(loaded.createdAt()).isNotNull();

    List<Posting> postings = transactionRepository.findPostings(txnId);
    assertThat(postings).hasSize(2);
    assertThat(postings)
        .extracting(p -> p.amount().intValueExact())
        .containsExactlyInAnyOrder(-30, 30);
  }

  @Test
  void updateHeaderReplacesTheEditableFieldsLeavingCreatedAtUntouched() {
    long payeeId = payeeRepository.insert("Rewe");
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null,
                LocalDate.of(2026, 6, 1),
                payeeId,
                "before",
                "pending_review",
                null,
                null,
                null));
    long newPayeeId = payeeRepository.insert("Migros");
    final OffsetDateTime createdAt =
        transactionRepository.findById(txnId).orElseThrow().createdAt();

    transactionRepository.updateHeader(
        new Transaction(
            txnId, LocalDate.of(2026, 6, 3), newPayeeId, "after", CONFIRMED, null, null, null));

    Transaction loaded = transactionRepository.findById(txnId).orElseThrow();
    assertThat(loaded.date()).isEqualTo(LocalDate.of(2026, 6, 3));
    assertThat(loaded.payeeId()).isEqualTo(newPayeeId);
    assertThat(loaded.note()).isEqualTo("after");
    assertThat(loaded.lifecycle()).isEqualTo(CONFIRMED);
    // The insert-only creation stamp is never rewritten by an edit.
    assertThat(loaded.createdAt()).isEqualTo(createdAt);
  }

  @Test
  void deletePostingsRemovesEveryLegOfTheTransaction() {
    long cash = insertCashAccount(EUR);
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null, LocalDate.of(2026, 6, 1), null, null, CONFIRMED, null, null, null));
    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("-5.0000"), null, UNRECONCILED, null));
    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("5.0000"), null, UNRECONCILED, null));
    assertThat(transactionRepository.findPostings(txnId)).hasSize(2);

    transactionRepository.deletePostings(txnId);

    // The transaction row survives (edit re-threads it); only its legs are gone.
    assertThat(transactionRepository.findPostings(txnId)).isEmpty();
    assertThat(transactionRepository.findById(txnId)).isPresent();
  }

  @Test
  void softDeleteStampsDeletedAtOnceAndIsIdempotentThereafter() {
    long cash = insertCashAccount(EUR);
    long txnId =
        transactionRepository.insertTransaction(
            new Transaction(
                null, LocalDate.of(2026, 6, 2), null, null, CONFIRMED, null, null, null));
    transactionRepository.insertPosting(
        new Posting(null, txnId, cash, new BigDecimal("0.0000"), null, UNRECONCILED, null));

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

  @Test
  void currenciesRoundTripFromTheSeed() {
    List<Currency> currencies = currencyRepository.findAll();
    // Seeded in V2; the natural key is the ISO code, and the list is ordered by it.
    assertThat(currencies).extracting(Currency::code).contains(EUR, CHF).isSorted();
    Currency eur = currencies.stream().filter(c -> EUR.equals(c.code())).findFirst().orElseThrow();
    assertThat(eur.name()).isEqualTo("Euro");
    assertThat(eur.minorUnits()).isEqualTo(2);
    assertThat(eur.symbol()).isEqualTo("€");
  }

  @Test
  void insertingAlreadySeededCurrencyRejects() {
    // The natural key is the ISO code, so re-inserting a seeded currency hits the primary key —
    // the real DB constraint the add-a-currency operation relies on to refuse duplicates.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> currencyService.insert(EUR, 2, "€", "Euro"))
        .withMessageContaining("already exists");
  }
}
