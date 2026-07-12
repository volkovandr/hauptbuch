package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Unit tier (plan §1.5): the engine's orchestration and validation logic with the DB mocked away.
 * This is where the cheap, high-value invariant checks live — {@link LedgerService} must reject
 * unbalanced or non-leaf input <em>before</em> the database (data-model §8). The SQL-resident half
 * of those invariants is verified separately against real Postgres in the SQL-logic suite.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String NINETY_FIVE = "95.00";
  private static final String MINUS_5 = "-5.00";

  // Account ids used across tests. Two EUR accounts, one CHF, one parent (non-leaf).
  private static final long CASH_EUR = 10L;
  private static final long FOOD_EUR = 11L;
  private static final long CARD_CHF = 12L;
  private static final long FOOD_PARENT = 99L;

  @Mock private SettingsService settingsService;
  @Mock private AccountService accountService;
  @Mock private TransactionRepository transactionRepository;

  private LedgerService ledgerService;

  @BeforeEach
  void setUp() {
    ledgerService = new LedgerService(settingsService, accountService, transactionRepository);
  }

  private void stubBaseCurrency(String code) {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(code));
  }

  private void stubAccount(long id, String currency) {
    when(accountService.findById(id))
        .thenReturn(
            Optional.of(
                new Account(
                    id, "acct" + id, "asset", null, currency, null, null, null, null, false)));
  }

  @Test
  void recordsBalancedSingleCurrencyTransaction() {
    stubBaseCurrency(EUR);
    stubAccount(CASH_EUR, EUR);
    stubAccount(FOOD_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());
    when(transactionRepository.insertTransaction(any())).thenReturn(500L);

    long id =
        ledgerService.recordTransaction(
            TransactionDraft.confirmed(
                LocalDate.of(2026, 6, 1),
                null,
                "coffee",
                List.of(
                    PostingDraft.of(CASH_EUR, new BigDecimal(MINUS_5)),
                    PostingDraft.of(FOOD_EUR, new BigDecimal("5.00")))));

    assertThat(id).isEqualTo(500L);
    verify(transactionRepository, times(2)).insertPosting(any());
  }

  @Test
  void rejectsUnbalancedSingleCurrencyTransactionBeforeAnyInsert() {
    stubBaseCurrency(EUR);
    stubAccount(CASH_EUR, EUR);
    stubAccount(FOOD_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());

    assertThatExceptionOfType(UnbalancedTransactionException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                        List.of(
                            PostingDraft.of(CASH_EUR, new BigDecimal(MINUS_5)),
                            PostingDraft.of(FOOD_EUR, new BigDecimal("4.00"))))))
        .withMessageContaining("does not sum to zero");

    verify(transactionRepository, never()).insertTransaction(any());
    verify(transactionRepository, never()).insertPosting(any());
  }

  @Test
  void refusesToRecordWhileBaseCurrencyIsUnset() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                        List.of(
                            PostingDraft.of(CASH_EUR, new BigDecimal(MINUS_5)),
                            PostingDraft.of(FOOD_EUR, new BigDecimal("5.00"))))))
        .withMessageContaining("Base currency is not set");

    verify(transactionRepository, never()).insertTransaction(any());
  }

  @Test
  void rejectsPostingToNonLeafAccount() {
    stubBaseCurrency(EUR);
    stubAccount(CASH_EUR, EUR);
    when(accountService.findById(FOOD_PARENT))
        .thenReturn(
            Optional.of(
                new Account(
                    FOOD_PARENT, "Food", "expense", null, EUR, null, null, null, null, false)));
    when(accountService.findParentAccountIds()).thenReturn(List.of(FOOD_PARENT));

    assertThatExceptionOfType(UnbalancedTransactionException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                        List.of(
                            PostingDraft.of(CASH_EUR, new BigDecimal(MINUS_5)),
                            PostingDraft.of(FOOD_PARENT, new BigDecimal("5.00"))))))
        .withMessageContaining("leaves-only");

    verify(transactionRepository, never()).insertTransaction(any());
  }

  @Test
  void crossCurrencyRequiresBaseAmountOnEveryLeg() {
    stubBaseCurrency(EUR);
    stubAccount(CARD_CHF, CHF);
    stubAccount(CASH_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());

    assertThatExceptionOfType(UnbalancedTransactionException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                        // CHF leg has no base_amount — invalid for a cross-currency transaction.
                        List.of(
                            PostingDraft.of(CARD_CHF, new BigDecimal("-100.00")),
                            PostingDraft.ofCrossCurrency(
                                CASH_EUR,
                                new BigDecimal(NINETY_FIVE),
                                new BigDecimal(NINETY_FIVE))))))
        .withMessageContaining("missing its base_amount");
  }

  @Test
  void recordsParBalancedCrossCurrencyTransferWithNoFxResidual() {
    stubBaseCurrency(EUR);
    stubAccount(CARD_CHF, CHF);
    stubAccount(CASH_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());
    when(transactionRepository.insertTransaction(any())).thenReturn(600L);

    // 100 CHF arrived as €95; both base amounts given, summing to zero — no residual.
    ledgerService.recordTransaction(
        TransactionDraft.confirmed(
            LocalDate.of(2026, 6, 1),
            null,
            "transfer",
            List.of(
                PostingDraft.ofCrossCurrency(
                    CARD_CHF, new BigDecimal("-100.00"), new BigDecimal("-95.00")),
                PostingDraft.ofCrossCurrency(
                    CASH_EUR, new BigDecimal(NINETY_FIVE), new BigDecimal(NINETY_FIVE)))));

    // Exactly the two submitted legs — no FX gain/loss leg inserted.
    verify(transactionRepository, times(2)).insertPosting(any());
    verify(accountService, never()).findLeafUnderParentNamed(any(), any());
  }

  @Test
  void rejectsCrossCurrencyWhenBaseAmountsDoNotSumToZero() {
    stubBaseCurrency(EUR);
    stubAccount(CARD_CHF, CHF);
    stubAccount(CASH_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());

    // Base amounts sum to +2.00 (−95 + 97): a non-par conversion. The engine books no residual —
    // it rejects and the caller must add a manual FX gain/loss line (data-model §6.3, 2026-07-11).
    assertThatExceptionOfType(UnbalancedTransactionException.class)
        .isThrownBy(
            () ->
                ledgerService.recordTransaction(
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 1),
                        null,
                        "settle-up",
                        List.of(
                            PostingDraft.ofCrossCurrency(
                                CARD_CHF, new BigDecimal("-100.00"), new BigDecimal("-95.00")),
                            PostingDraft.ofCrossCurrency(
                                CASH_EUR, new BigDecimal("97.00"), new BigDecimal("97.00"))))))
        .withMessageContaining("does not balance in base");

    verify(transactionRepository, never()).insertPosting(any());
    verify(accountService, never()).findLeafUnderParentNamed(any(), any());
  }

  @Test
  void voidingLiveTransactionSoftDeletesIt() {
    when(transactionRepository.softDelete(42L)).thenReturn(1);

    ledgerService.voidTransaction(42L);

    verify(transactionRepository).softDelete(42L);
  }

  @Test
  void voidingAlreadyVoidedTransactionFails() {
    when(transactionRepository.softDelete(42L)).thenReturn(0);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ledgerService.voidTransaction(42L));
  }

  @Test
  void editingReThreadsTheLegs() {
    stubBaseCurrency(EUR);
    stubAccount(CASH_EUR, EUR);
    stubAccount(FOOD_EUR, EUR);
    when(accountService.findParentAccountIds()).thenReturn(List.of());
    when(transactionRepository.findById(80L))
        .thenReturn(
            Optional.of(
                new Transaction(
                    80L, LocalDate.of(2026, 6, 1), null, null, "confirmed", null, null, null)));

    ledgerService.editTransaction(
        80L,
        TransactionDraft.confirmed(
            LocalDate.of(2026, 6, 2),
            null,
            "edited",
            List.of(
                PostingDraft.of(CASH_EUR, new BigDecimal("-9.00")),
                PostingDraft.of(FOOD_EUR, new BigDecimal("9.00")))));

    verify(transactionRepository).updateHeader(any());
    verify(transactionRepository).deletePostings(80L);
    verify(transactionRepository, times(2)).insertPosting(any());
  }

  @Test
  void editingMissingTransactionFails() {
    stubBaseCurrency(EUR);
    when(transactionRepository.findById(anyLong())).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                ledgerService.editTransaction(
                    404L,
                    TransactionDraft.confirmed(
                        LocalDate.of(2026, 6, 2),
                        null,
                        null,
                        List.of(
                            PostingDraft.of(CASH_EUR, new BigDecimal("-9.00")),
                            PostingDraft.of(FOOD_EUR, new BigDecimal("9.00"))))));
  }
}
