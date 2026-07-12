package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * Unit tier (plan §1.5): the engine-side fulfilment of the {@code accounts} opening-balance SPI
 * builds the textbook stage-3 draft — {@code Account +X / Opening Balances −X}, sum-to-zero by
 * construction, dated the opening day (data-model T-DM-4) — and sends it through {@link
 * LedgerService#recordTransaction}, never around it.
 */
@ExtendWith(MockitoExtension.class)
class LedgerOpeningBalanceRecorderTest {

  private static final String CHF = "CHF";
  private static final long CARD = 12L;
  private static final long OPENING_BALANCES_CHF = 5L;
  private static final LocalDate OPENED = LocalDate.of(2026, 7, 1);

  @Mock private AccountService accountService;
  @Mock private LedgerService ledgerService;

  private LedgerOpeningBalanceRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new LedgerOpeningBalanceRecorder(accountService, ledgerService);
  }

  @Test
  void booksTheBalancedOpeningTransactionAgainstThePerCurrencyLeaf() {
    when(accountService.findById(CARD))
        .thenReturn(
            Optional.of(
                new Account(CARD, "Card", "asset", null, CHF, 210, OPENED, null, null, false)));
    when(accountService.findLeafUnderParentNamed(
            LedgerOpeningBalanceRecorder.OPENING_BALANCES_PARENT, CHF))
        .thenReturn(
            Optional.of(
                new Account(
                    OPENING_BALANCES_CHF,
                    "Opening Balances CHF",
                    "equity",
                    1L,
                    CHF,
                    null,
                    null,
                    null,
                    null,
                    false)));
    when(ledgerService.recordTransaction(any())).thenReturn(900L);

    long txnId = recorder.recordOpeningBalance(CARD, new BigDecimal("250.00"), OPENED);

    assertThat(txnId).isEqualTo(900L);
    ArgumentCaptor<TransactionDraft> captor = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(captor.capture());
    TransactionDraft draft = captor.getValue();
    assertThat(draft.date()).isEqualTo(OPENED);
    assertThat(draft.note()).isEqualTo("Opening balance");
    assertThat(draft.postings()).hasSize(2);
    assertThat(draft.postings().get(0).accountId()).isEqualTo(CARD);
    assertThat(draft.postings().get(0).amount()).isEqualByComparingTo("250.00");
    assertThat(draft.postings().get(1).accountId()).isEqualTo(OPENING_BALANCES_CHF);
    assertThat(draft.postings().get(1).amount()).isEqualByComparingTo("-250.00");
  }

  @Test
  void failsLoudlyWhenTheAccountOrLeafIsMissing() {
    when(accountService.findById(CARD)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> recorder.recordOpeningBalance(CARD, BigDecimal.ONE, OPENED));

    when(accountService.findById(CARD))
        .thenReturn(
            Optional.of(
                new Account(CARD, "Card", "asset", null, CHF, 210, OPENED, null, null, false)));
    when(accountService.findLeafUnderParentNamed(
            LedgerOpeningBalanceRecorder.OPENING_BALANCES_PARENT, CHF))
        .thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> recorder.recordOpeningBalance(CARD, BigDecimal.ONE, OPENED))
        .withMessageContaining("Opening Balances");

    verify(ledgerService, never()).recordTransaction(any());
  }
}
