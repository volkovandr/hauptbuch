package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEditModel;
import volkovandr.hauptbuch.operations.DockEditService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.statements.ExtraReview.Boundary;

/**
 * Unit tier: an extra's Edit, Move to account and Void (statements.md §6.3–6.4, slice d2) with
 * {@code operations} and {@code ledger} mocked — each is checked against the live review, edits
 * book through the register's commit path, and nothing is ever matched.
 */
@ExtendWith(MockitoExtension.class)
class StatementExtraDockServiceTest {

  private static final long STATEMENT = 4L;
  private static final long STATEMENT_ACCOUNT = 7L;
  private static final long OTHER_EUR = 9L;
  private static final long OTHER_CHF = 11L;
  private static final long TRANSACTION = 50L;
  private static final long LEG = 60L;

  @Mock private StatementService statementService;
  @Mock private StatementReviewService reviewService;
  @Mock private AccountService accountService;
  @Mock private DockEditService dockEditService;
  @Mock private DockCommitService dockCommitService;
  @Mock private LedgerService ledgerService;

  private StatementExtraDockService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementExtraDockService(
            statementService,
            reviewService,
            accountService,
            dockEditService,
            dockCommitService,
            ledgerService);
  }

  private static Account account(long id, String name, String currency) {
    return new Account(
        id, name, "asset", null, currency, null, null, null, null, false, false, false);
  }

  private void extraOnStatement() {
    StatementExtra extra =
        new StatementExtra(
            LEG, TRANSACTION, LocalDate.of(2026, 5, 19), "ShopAaa", "weekly", new BigDecimal("-3"));
    when(reviewService.review(STATEMENT))
        .thenReturn(new StatementReview(List.of(), List.of(new ExtraReview(extra, Boundary.NONE))));
  }

  private void statementOnAccount() {
    when(statementService.get(STATEMENT))
        .thenReturn(
            new Statement(
                STATEMENT,
                1L,
                STATEMENT_ACCOUNT,
                Statement.STATE_NEW,
                "f.csv",
                "p",
                null,
                null,
                null,
                null,
                null,
                null));
  }

  private static DockEditModel model(long fundingAccount, String categoryAmount) {
    return new DockEditModel(
        TRANSACTION,
        LocalDate.of(2026, 5, 19),
        fundingAccount,
        "BankAaa-EUR",
        "ShopAaa - Berlin",
        "3,00",
        8L,
        "Food",
        categoryAmount == null ? null : "CHF",
        categoryAmount,
        null,
        null,
        "weekly",
        List.of(new TransactionTag(3L, "Car:Passat")));
  }

  private static DockInput typed(String amount) {
    return new DockInput(
        LocalDate.of(2026, 5, 19),
        "ShopAaa",
        8L,
        "Food",
        null,
        null,
        null,
        null,
        "weekly",
        List.of(3L),
        amount,
        null,
        null,
        null);
  }

  @Test
  void prefillOpensTheExtrasTransactionWithItsEditableAmount() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));
    when(ledgerService.labelsForTagIds(List.of(3L))).thenReturn(Map.of(3L, "Car:Passat"));

    DockPrefill dock = service.prefill(STATEMENT, LEG);

    assertThat(dock.kind()).isEqualTo(DockPrefill.Kind.EXTRA);
    assertThat(dock.amountEditable()).isTrue();
    assertThat(dock.amount()).isEqualTo("3,00");
    assertThat(dock.input().payeeText()).isEqualTo("ShopAaa - Berlin");
    assertThat(dock.tags()).extracting(TransactionTag::label).containsExactly("Car:Passat");
    assertThat(dock.path(STATEMENT)).isEqualTo("/statements/4/extras/60/edit");
  }

  @Test
  void prefillRefusesCrossCurrencyTransaction() {
    extraOnStatement();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, "3,30"));

    assertThatThrownBy(() -> service.prefill(STATEMENT, LEG))
        .isInstanceOf(StatementFormatException.class)
        .isInstanceOf(RegisterOnlyException.class);
  }

  @Test
  void prefillRefusesAnExtraThatIsNoLongerOne() {
    when(reviewService.review(STATEMENT)).thenReturn(new StatementReview(List.of(), List.of()));

    assertThatThrownBy(() -> service.prefill(STATEMENT, LEG))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void editBooksTheDocksFieldsOnTheStatementsAccountAndMatchesNothing() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));

    service.edit(STATEMENT, LEG, typed("4,00"));

    ArgumentCaptor<DockEntry> entry = ArgumentCaptor.forClass(DockEntry.class);
    verify(dockCommitService).commit(entry.capture());
    assertThat(entry.getValue().transactionId()).isEqualTo(TRANSACTION);
    assertThat(entry.getValue().accountId()).isEqualTo(STATEMENT_ACCOUNT);
    assertThat(entry.getValue().amount()).isEqualTo("4,00");
    assertThat(entry.getValue().tagIds()).containsExactly(3L);
  }

  @Test
  void editRefusedByTheLedgerShowsTheReason() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));
    when(dockCommitService.commit(any()))
        .thenThrow(new IllegalArgumentException("An amount is required"));

    assertThatThrownBy(() -> service.edit(STATEMENT, LEG, typed("")))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("An amount is required");
  }

  @Test
  void moveEditsTheTransactionOntoTheTargetAccountKeepingItsFields() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));
    when(accountService.findById(STATEMENT_ACCOUNT))
        .thenReturn(Optional.of(account(STATEMENT_ACCOUNT, "A", "EUR")));
    when(statementService.statementAccounts()).thenReturn(List.of(account(OTHER_EUR, "B", "EUR")));

    service.move(STATEMENT, LEG, OTHER_EUR);

    ArgumentCaptor<DockEntry> entry = ArgumentCaptor.forClass(DockEntry.class);
    verify(dockCommitService).commit(entry.capture());
    assertThat(entry.getValue().transactionId()).isEqualTo(TRANSACTION);
    assertThat(entry.getValue().accountId()).isEqualTo(OTHER_EUR);
    assertThat(entry.getValue().amount()).isEqualTo("3,00");
    assertThat(entry.getValue().payeeText()).isEqualTo("ShopAaa - Berlin");
    assertThat(entry.getValue().tagIds()).containsExactly(3L);
  }

  @Test
  void moveRefusesAnAccountInAnotherCurrency() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));
    when(accountService.findById(STATEMENT_ACCOUNT))
        .thenReturn(Optional.of(account(STATEMENT_ACCOUNT, "A", "EUR")));
    when(statementService.statementAccounts()).thenReturn(List.of(account(OTHER_CHF, "C", "CHF")));

    assertThatThrownBy(() -> service.move(STATEMENT, LEG, OTHER_CHF))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("EUR");

    verify(dockCommitService, never()).commit(any());
  }

  @Test
  void moveRefusesAnAccountThatCannotHoldIt() {
    extraOnStatement();
    statementOnAccount();
    when(dockEditService.load(TRANSACTION)).thenReturn(model(STATEMENT_ACCOUNT, null));
    when(accountService.findById(STATEMENT_ACCOUNT))
        .thenReturn(Optional.of(account(STATEMENT_ACCOUNT, "A", "EUR")));
    when(statementService.statementAccounts()).thenReturn(List.of());

    assertThatThrownBy(() -> service.move(STATEMENT, LEG, 99L))
        .isInstanceOf(StatementFormatException.class);

    verify(dockCommitService, never()).commit(any());
  }

  @Test
  void voidVoidsTheExtrasTransaction() {
    extraOnStatement();

    service.voidExtra(STATEMENT, LEG);

    verify(dockCommitService).voidTransaction(TRANSACTION);
  }

  @Test
  void voidOfAnExtraThatIsGoneVoidsNothing() {
    when(reviewService.review(STATEMENT)).thenReturn(new StatementReview(List.of(), List.of()));

    assertThatThrownBy(() -> service.voidExtra(STATEMENT, LEG))
        .isInstanceOf(StatementFormatException.class);

    verify(dockCommitService, never()).voidTransaction(LEG);
  }
}
