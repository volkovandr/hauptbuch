package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.OpeningBalanceRecorder;
import volkovandr.hauptbuch.importer.StagedCommitData.CommitData;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.OpeningBalanceView;

/**
 * Unit tier (CLAUDE.md §6): {@link OpeningBalanceCommit} with the ledger mocked — each of Money's
 * opening-balance outcomes (import.md §5.1; plan f2): keep Hauptbuch's, take Money's (voiding the
 * existing one), override with a typed amount, the fall-back to the proposal when no choice was
 * recorded, and the person-leaf skip.
 */
@ExtendWith(MockitoExtension.class)
class OpeningBalanceCommitTest {

  private static final long MAPPED_ID = 100L;

  @Mock LedgerService ledgerService;
  @Mock OpeningBalanceRecorder openingBalanceRecorder;

  private OpeningBalanceCommit commit() {
    return new OpeningBalanceCommit(ledgerService, openingBalanceRecorder);
  }

  @Test
  void keepHauptbuchBooksNothing() {
    CommitData data =
        data(row(OpeningBalanceChoice.KEEP_HAUPTBUCH, null), account(false), staged("500.00"));

    assertThat(commit().apply(data)).isZero();
    verify(openingBalanceRecorder, never()).recordOpeningBalance(anyLong(), any(), any());
    verify(ledgerService, never()).voidTransaction(anyLong());
  }

  @Test
  void takeMoneyVoidsHauptbuchsOwnAndBooksMoneysFigure() {
    when(ledgerService.openingBalanceOf(MAPPED_ID))
        .thenReturn(
            Optional.of(
                new OpeningBalanceView(42L, LocalDate.of(2005, 1, 1), new BigDecimal("0.00"))));
    CommitData data =
        data(row(OpeningBalanceChoice.TAKE_MONEY, null), account(false), staged("500.00"));

    assertThat(commit().apply(data)).isEqualTo(1);
    verify(ledgerService).voidTransaction(42L);
    verify(openingBalanceRecorder)
        .recordOpeningBalance(MAPPED_ID, new BigDecimal("500.00"), LocalDate.of(2004, 1, 1));
  }

  @Test
  void overrideBooksTheTypedAmount() {
    when(ledgerService.openingBalanceOf(MAPPED_ID)).thenReturn(Optional.empty());
    CommitData data =
        data(
            row(OpeningBalanceChoice.OVERRIDE, new BigDecimal("123.45")),
            account(false),
            staged("500.00"));

    assertThat(commit().apply(data)).isEqualTo(1);
    verify(openingBalanceRecorder)
        .recordOpeningBalance(MAPPED_ID, new BigDecimal("123.45"), LocalDate.of(2004, 1, 1));
  }

  @Test
  void withNoRecordedChoiceFallsBackToTheProposalWhichBringsMoneysIn() {
    when(ledgerService.openingBalanceOf(MAPPED_ID)).thenReturn(Optional.empty());
    CommitData data = data(row(null, null), account(false), staged("500.00"));

    assertThat(commit().apply(data)).isEqualTo(1);
    verify(openingBalanceRecorder)
        .recordOpeningBalance(MAPPED_ID, new BigDecimal("500.00"), LocalDate.of(2004, 1, 1));
  }

  @Test
  void personLeafIsSkipped() {
    CommitData data =
        data(row(OpeningBalanceChoice.TAKE_MONEY, null), account(true), staged("500.00"));

    assertThat(commit().apply(data)).isZero();
    verifyNoInteractions(openingBalanceRecorder);
  }

  // --- fixtures -----------------------------------------------------------

  private static CommitData data(
      ImportAccount row, Account account, ImportStagedOpeningBalance staged) {
    return new CommitData(
        List.of(),
        Map.of(),
        new StagedTransactionResolver.Maps("EUR", Map.of(), Map.of(), Map.of(), Map.of()),
        Map.of(MAPPED_ID, account),
        List.of(row),
        List.of(staged),
        Set.of());
  }

  private static ImportAccount row(String choice, BigDecimal overrideAmount) {
    return new ImportAccount(
        1L, 7L, "BankAaa", MAPPED_ID, null, "EUR", false, choice, overrideAmount);
  }

  private static ImportStagedOpeningBalance staged(String amount) {
    return new ImportStagedOpeningBalance(
        "BankAaa", LocalDate.of(2004, 1, 1), new BigDecimal(amount));
  }

  private static Account account(boolean personLeaf) {
    return new Account(
        MAPPED_ID, "acc", "asset", null, "EUR", null, null, null, null, false, personLeaf, false);
  }
}
