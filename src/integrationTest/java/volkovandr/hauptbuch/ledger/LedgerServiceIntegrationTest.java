package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Integration tier (plan §1.5): the engine records, voids, and edits real transactions end to end
 * against Postgres — the persistence path the unit suite mocks away. The invariant <em>logic</em>
 * is unit-tested ({@link LedgerServiceTest}); the SQL-resident invariants are checked in the
 * SQL-logic suite. This test proves the wiring: a balanced draft becomes balanced rows.
 *
 * <p>{@code @Transactional} rolls each test back, isolating them on the reused container —
 * including the base-currency write, which is write-once and would otherwise lock the shared book.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerServiceIntegrationTest {

  private static final String MINUS_5 = "-5.00";
  private static final String PLUS_5 = "5.00";
  private static final String EUR = "EUR";

  @Autowired JdbcClient jdbcClient;
  @Autowired LedgerService ledgerService;
  @Autowired SettingsService settingsService;
  @Autowired AccountService accountService;
  @Autowired TransactionRepository transactionRepository;

  private long cashEur;
  private long foodEur;
  private long cardChf;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    cashEur = insertAccount("Cash", "asset", EUR);
    foodEur = insertAccount("Food EUR", "expense", EUR);
    cardChf = insertAccount("Card", "asset", "CHF");
  }

  private long insertAccount(String name, String type, String currency) {
    return jdbcClient
        .sql(
            """
            insert into account (name, type, currency_code) values (:n, :t, :c)
            returning account_id
            """)
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .query(Long.class)
        .single();
  }

  private BigDecimal nativeBalance(long accountId) {
    return jdbcClient
        .sql(
            """
            select coalesce(sum(p.amount), 0)
            from posting p
            join transaction t on p.transaction_id = t.transaction_id
            where p.account_id = :accountId and t.deleted_at is null
            """)
        .param("accountId", accountId)
        .query(BigDecimal.class)
        .single();
  }

  @Test
  void recordsBalancedSingleCurrencyPurchase() {
    long txnId =
        ledgerService.recordTransaction(
            TransactionDraft.confirmed(
                LocalDate.of(2026, 6, 1),
                null,
                "coffee",
                List.of(
                    PostingDraft.of(cashEur, new BigDecimal(MINUS_5)),
                    PostingDraft.of(foodEur, new BigDecimal(PLUS_5)))));

    assertThat(transactionRepository.findPostings(txnId)).hasSize(2);
    assertThat(nativeBalance(cashEur)).isEqualByComparingTo(MINUS_5);
    assertThat(nativeBalance(foodEur)).isEqualByComparingTo(PLUS_5);
  }

  @Test
  void booksResidualOfNonParConversionToBaseFxLeaf() {
    // 100 CHF leaves the card; €97 arrives; the booked base values imply a +2.00 gain.
    long txnId =
        ledgerService.recordTransaction(
            TransactionDraft.confirmed(
                LocalDate.of(2026, 6, 1),
                null,
                "settle-up",
                List.of(
                    PostingDraft.ofCrossCurrency(
                        cardChf, new BigDecimal("-100.00"), new BigDecimal("-95.00")),
                    PostingDraft.ofCrossCurrency(
                        cashEur, new BigDecimal("97.00"), new BigDecimal("97.00")))));

    List<Posting> postings = transactionRepository.findPostings(txnId);
    assertThat(postings).hasSize(3);

    Account fxLeaf = accountService.findLeafUnderParentNamed("FX gain/loss", EUR).orElseThrow();
    Posting fxLeg =
        postings.stream()
            .filter(p -> p.accountId().equals(fxLeaf.accountId()))
            .findFirst()
            .orElseThrow();
    assertThat(fxLeg.amount()).isEqualByComparingTo("-2.00");
    assertThat(fxLeg.baseAmount()).isEqualByComparingTo("-2.00");

    // The legs balance in base.
    BigDecimal baseSum =
        postings.stream().map(Posting::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(baseSum).isEqualByComparingTo("0.00");
  }

  @Test
  void voidingRemovesTransactionFromLiveBalances() {
    long txnId =
        ledgerService.recordTransaction(
            TransactionDraft.confirmed(
                LocalDate.of(2026, 6, 1),
                null,
                null,
                List.of(
                    PostingDraft.of(cashEur, new BigDecimal(MINUS_5)),
                    PostingDraft.of(foodEur, new BigDecimal(PLUS_5)))));
    assertThat(nativeBalance(cashEur)).isEqualByComparingTo(MINUS_5);

    ledgerService.voidTransaction(txnId);

    // Soft-deleted: the transaction is stamped and no longer counts toward live balances.
    assertThat(transactionRepository.findById(txnId).orElseThrow().deletedAt()).isNotNull();
    assertThat(nativeBalance(cashEur)).isEqualByComparingTo("0");
  }

  @Test
  void editingReplacesTheLegs() {
    long txnId =
        ledgerService.recordTransaction(
            TransactionDraft.confirmed(
                LocalDate.of(2026, 6, 1),
                null,
                "before",
                List.of(
                    PostingDraft.of(cashEur, new BigDecimal(MINUS_5)),
                    PostingDraft.of(foodEur, new BigDecimal(PLUS_5)))));

    ledgerService.editTransaction(
        txnId,
        TransactionDraft.confirmed(
            LocalDate.of(2026, 6, 3),
            null,
            "after",
            List.of(
                PostingDraft.of(cashEur, new BigDecimal("-8.00")),
                PostingDraft.of(foodEur, new BigDecimal("8.00")))));

    Transaction reloaded = transactionRepository.findById(txnId).orElseThrow();
    assertThat(reloaded.date()).isEqualTo(LocalDate.of(2026, 6, 3));
    assertThat(reloaded.note()).isEqualTo("after");
    assertThat(transactionRepository.findPostings(txnId)).hasSize(2);
    assertThat(nativeBalance(cashEur)).isEqualByComparingTo("-8.00");
  }

  @Test
  void refusesToRecordWhenBaseCurrencyIsUnset() {
    // Clear the base currency this single test set, to exercise the precondition.
    jdbcClient.sql("update settings set base_currency = null where settings_id = 1").update();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                        List.of(
                            PostingDraft.of(cashEur, new BigDecimal(MINUS_5)),
                            PostingDraft.of(foodEur, new BigDecimal(PLUS_5))))));
  }
}
