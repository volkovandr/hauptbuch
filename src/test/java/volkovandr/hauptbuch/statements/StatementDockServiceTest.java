package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.operations.DockPrefillService;
import volkovandr.hauptbuch.operations.GhostSuggestion;

/**
 * Unit tier: the statement page's dock (statements.md §6.4) with {@code operations} and {@code
 * ledger} mocked — the pre-fill from a missing line, and that a save books with the bank's amount,
 * matches the new leg, and matches nothing when the booking is refused.
 */
@ExtendWith(MockitoExtension.class)
class StatementDockServiceTest {

  private static final long STATEMENT = 4L;
  private static final long LINE_ID = 10L;
  private static final long ACCOUNT = 7L;
  private static final long TRANSACTION = 99L;
  private static final long LEG = 123L;
  private static final BigDecimal AMOUNT = new BigDecimal("-3.50");

  @Mock private StatementService statementService;
  @Mock private StatementMatchService matchService;
  @Mock private PayeeService payeeService;
  @Mock private DockPrefillService dockPrefillService;
  @Mock private DockCommitService dockCommitService;
  @Mock private LedgerService ledgerService;

  private StatementDockService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementDockService(
            statementService,
            matchService,
            payeeService,
            dockPrefillService,
            dockCommitService,
            ledgerService);
  }

  private static StatementLine line() {
    return new StatementLine(
        LINE_ID,
        0,
        LocalDate.of(2026, 5, 20),
        null,
        AMOUNT,
        "ShopAaa Berlin",
        "Card payment",
        "Groceries",
        "raw",
        null);
  }

  private static LineReview review(LineStatus status) {
    return new LineReview(line(), status, null, List.of());
  }

  private static DockInput input(Long categoryId) {
    return new DockInput(
        LocalDate.of(2026, 5, 20),
        "ShopAaa",
        categoryId,
        "Food",
        null,
        null,
        null,
        null,
        "note",
        List.of(3L));
  }

  private void statementOnAccount() {
    when(statementService.get(STATEMENT))
        .thenReturn(
            new Statement(
                STATEMENT,
                1L,
                ACCOUNT,
                Statement.STATE_NEW,
                "2026-05.csv",
                "path",
                null,
                null,
                null,
                null,
                null,
                null));
  }

  @Test
  void prefillsDateAmountLongestPayeeAndItsLastCategory() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(payeeService.longestNameIn("ShopAaa Berlin Card payment"))
        .thenReturn(Optional.of(new Payee(5L, "ShopAaa", "Berlin", null, null)));
    when(payeeService.entryValueFor(5L)).thenReturn(Optional.of("ShopAaa - Berlin"));
    when(dockPrefillService.lastCategoryOf(5L))
        .thenReturn(Optional.of(new GhostSuggestion(8L, "Food")));

    DockPrefill prefill = service.prefill(STATEMENT, LINE_ID);

    assertThat(prefill.input().date()).isEqualTo(LocalDate.of(2026, 5, 20));
    assertThat(prefill.amount()).isEqualTo("3,50");
    assertThat(prefill.input().payeeText()).isEqualTo("ShopAaa - Berlin");
    assertThat(prefill.input().categoryText()).isEqualTo("Food");
    assertThat(prefill.input().categoryId()).isEqualTo(8L);
    assertThat(prefill.bankCategory()).isEqualTo("Groceries");
  }

  @Test
  void prefillWithoutKnownPayeeLeavesPayeeAndCategoryEmpty() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(payeeService.longestNameIn(any())).thenReturn(Optional.empty());

    DockPrefill prefill = service.prefill(STATEMENT, LINE_ID);

    assertThat(prefill.input().payeeText()).isEmpty();
    assertThat(prefill.input().categoryText()).isEmpty();
    assertThat(prefill.input().categoryId()).isNull();
    assertThat(prefill.bankText()).isEqualTo("ShopAaa Berlin Card payment");
  }

  @Test
  void reopenKeepsWhatWasTypedWithTheTagLabelsAndTheReason() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(ledgerService.labelsForTagIds(List.of(3L))).thenReturn(Map.of(3L, "Car:Passat"));

    DockPrefill dock = service.reopen(STATEMENT, LINE_ID, input(null), "No category");

    assertThat(dock.input().payeeText()).isEqualTo("ShopAaa");
    assertThat(dock.input().note()).isEqualTo("note");
    assertThat(dock.tags()).extracting(TransactionTag::label).containsExactly("Car:Passat");
    assertThat(dock.error()).isEqualTo("No category");
  }

  @Test
  void reopenRefusesLineThatIsNoLongerMissing() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.EXACT));

    assertThatThrownBy(() -> service.reopen(STATEMENT, LINE_ID, input(null), "x"))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void prefillRefusesLineThatIsNotMissing() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.EXACT));

    assertThatThrownBy(() -> service.prefill(STATEMENT, LINE_ID))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void saveBooksWithTheBanksAmountThenMatchesTheLegOnTheStatementsAccount() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(dockCommitService.commitWithFundingAmount(any(), eq(AMOUNT))).thenReturn(TRANSACTION);
    when(ledgerService.findPostings(TRANSACTION))
        .thenReturn(
            List.of(
                new Posting(LEG, TRANSACTION, ACCOUNT, AMOUNT, null, "unreconciled", null),
                new Posting(
                    LEG + 1, TRANSACTION, 8L, AMOUNT.negate(), null, "unreconciled", null)));

    service.createMissing(STATEMENT, LINE_ID, input(8L));

    ArgumentCaptor<DockEntry> entry = ArgumentCaptor.forClass(DockEntry.class);
    verify(dockCommitService).commitWithFundingAmount(entry.capture(), eq(AMOUNT));
    assertThat(entry.getValue().transactionId()).isNull();
    assertThat(entry.getValue().accountId()).isEqualTo(ACCOUNT);
    assertThat(entry.getValue().categoryId()).isEqualTo(8L);
    assertThat(entry.getValue().payeeText()).isEqualTo("ShopAaa");
    assertThat(entry.getValue().tagIds()).containsExactly(3L);
    verify(matchService).link(STATEMENT, LINE_ID, LEG);
  }

  @Test
  void saveWithoutCategoryOrPersonBooksNothing() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));

    assertThatThrownBy(() -> service.createMissing(STATEMENT, LINE_ID, input(null)))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("category");

    verify(dockCommitService, never()).commitWithFundingAmount(any(), any());
  }

  @Test
  void saveRefusedByTheLedgerMatchesNothingAndShowsTheReason() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(dockCommitService.commitWithFundingAmount(any(), any()))
        .thenThrow(new IllegalArgumentException("A CHF amount is required"));

    assertThatThrownBy(() -> service.createMissing(STATEMENT, LINE_ID, input(8L)))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("A CHF amount is required");

    verify(matchService, never()).link(anyLong(), anyLong(), anyLong());
  }

  @Test
  void saveRefusedAsUnbalancedMatchesNothing() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MISSING));
    when(dockCommitService.commitWithFundingAmount(any(), any()))
        .thenThrow(new UnbalancedTransactionException("does not balance"));

    assertThatThrownBy(() -> service.createMissing(STATEMENT, LINE_ID, input(8L)))
        .isInstanceOf(StatementFormatException.class);

    verify(matchService, never()).link(anyLong(), anyLong(), anyLong());
  }

  @Test
  void saveOnLineNoLongerMissingBooksNothing() {
    when(matchService.lineOf(STATEMENT, LINE_ID)).thenReturn(review(LineStatus.MATCHED));

    assertThatThrownBy(() -> service.createMissing(STATEMENT, LINE_ID, input(8L)))
        .isInstanceOf(StatementFormatException.class);

    verify(dockCommitService, never()).commitWithFundingAmount(any(), any());
  }
}
