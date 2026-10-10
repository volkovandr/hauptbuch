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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEditModel;
import volkovandr.hauptbuch.operations.DockEditService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.operations.DockPrefillService;
import volkovandr.hauptbuch.statements.ProposedCandidate.Tier;

/** Unit tier: amending a proposed amount-differs or wrong-account transaction (slice d2). */
@ExtendWith(MockitoExtension.class)
class StatementDockAmendServiceTest {

  private static final long OLD_TRANSACTION = 50L;
  private static final long OLD_LEG = 60L;
  private static final long OTHER_ACCOUNT = 9L;
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
  @Mock private DockEditService dockEditService;
  @Mock private LedgerService ledgerService;
  @Mock private StatementCrossCurrencyService crossCurrencyService;

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
            dockEditService,
            ledgerService,
            crossCurrencyService);
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
        List.of(3L),
        null,
        null,
        null,
        null);
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

  private static LineReview proposal(Tier tier, long onAccount, boolean matchedElsewhere) {
    StatementCandidate candidate =
        new StatementCandidate(
            LINE_ID,
            OLD_LEG,
            OLD_TRANSACTION,
            onAccount,
            "BankBbb-EUR",
            tier == Tier.WRONG_ACCOUNT ? AMOUNT : new BigDecimal("-3.00"),
            LocalDate.of(2026, 5, 19),
            "ShopAaa",
            true,
            "unreconciled",
            matchedElsewhere,
            1);
    LineStatus status =
        tier == Tier.WRONG_ACCOUNT ? LineStatus.WRONG_ACCOUNT : LineStatus.AMOUNT_DIFFERS;
    return new LineReview(line(), status, null, List.of(new ProposedCandidate(candidate, tier)));
  }

  private static DockEditModel bookedModel(long fundingAccount) {
    return new DockEditModel(
        OLD_TRANSACTION,
        LocalDate.of(2026, 5, 19),
        fundingAccount,
        "BankBbb-EUR",
        "ShopAaa - Berlin",
        "3,00",
        8L,
        "Food",
        null,
        null,
        null,
        null,
        "weekly",
        List.of(new TransactionTag(3L, "Car:Passat")));
  }

  @Test
  void amendPrefillsTheBookedTransactionAndSaysWhatSaveChanges() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(ACCOUNT));
    when(ledgerService.labelsForTagIds(List.of(3L))).thenReturn(Map.of(3L, "Car:Passat"));

    DockPrefill dock = service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG);

    assertThat(dock.kind()).isEqualTo(DockPrefill.Kind.AMEND);
    assertThat(dock.postingId()).isEqualTo(OLD_LEG);
    assertThat(dock.amount()).isEqualTo("3,50");
    assertThat(dock.input().payeeText()).isEqualTo("ShopAaa - Berlin");
    assertThat(dock.input().categoryId()).isEqualTo(8L);
    assertThat(dock.input().note()).isEqualTo("weekly");
    assertThat(dock.tags()).extracting(TransactionTag::label).containsExactly("Car:Passat");
    assertThat(dock.notice()).isEqualTo("Booked as -3,00; saving sets it to the bank's -3,50.");
    assertThat(dock.path(STATEMENT)).isEqualTo("/statements/4/lines/10/amend/60");
  }

  @Test
  void wrongAccountPrefillSaysTheTransactionMovesToTheStatementsAccount() {
    statementOnAccount();
    when(statementService.accountName(ACCOUNT)).thenReturn("BankAaa-EUR");
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.WRONG_ACCOUNT, OTHER_ACCOUNT, true));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(OTHER_ACCOUNT));

    DockPrefill dock = service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG);

    assertThat(dock.notice())
        .isEqualTo(
            "Booked on BankBbb-EUR; saving moves it to BankAaa-EUR."
                + " It is matched on another statement, which the change removes.");
  }

  @Test
  void amendSavesAtTheBanksAmountOnTheStatementsAccountThenMatchesTheLeg() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.WRONG_ACCOUNT, OTHER_ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(OTHER_ACCOUNT));
    when(dockCommitService.commitWithFundingAmount(any(), eq(AMOUNT))).thenReturn(OLD_TRANSACTION);
    when(ledgerService.findPostings(OLD_TRANSACTION))
        .thenReturn(
            List.of(
                new Posting(LEG, OLD_TRANSACTION, ACCOUNT, AMOUNT, null, "unreconciled", null),
                new Posting(
                    LEG + 1, OLD_TRANSACTION, 8L, AMOUNT.negate(), null, "unreconciled", null)));

    service.amend(STATEMENT, LINE_ID, OLD_LEG, input(8L));

    ArgumentCaptor<DockEntry> entry = ArgumentCaptor.forClass(DockEntry.class);
    verify(dockCommitService).commitWithFundingAmount(entry.capture(), eq(AMOUNT));
    assertThat(entry.getValue().transactionId()).isEqualTo(OLD_TRANSACTION);
    assertThat(entry.getValue().accountId()).isEqualTo(ACCOUNT);
    assertThat(entry.getValue().categoryId()).isEqualTo(8L);
    verify(matchService).link(STATEMENT, LINE_ID, LEG);
  }

  private static DockEditModel crossCurrencyModel(long fundingAccount) {
    DockEditModel m = bookedModel(fundingAccount);
    return new DockEditModel(
        m.transactionId(),
        m.date(),
        m.accountId(),
        m.accountEntryText(),
        m.payeeText(),
        m.amount(),
        m.categoryId(),
        m.categoryName(),
        "CHF",
        "3,30",
        "3,10",
        m.transferDirection(),
        m.note(),
        m.tags());
  }

  @Test
  void amendOfCrossCurrencyAtDifferentFigureAsksForTheBaseAmountAgain() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(crossCurrencyModel(ACCOUNT));

    DockPrefill dock = service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG);

    assertThat(dock.input().baseAmount()).isEmpty();
    assertThat(dock.input().categoryAmount()).isEqualTo("3,30");
    assertThat(dock.notice()).endsWith("Enter the base amount for the bank's figure.");
  }

  @Test
  void amendOfCrossCurrencyOnTheWrongAccountKeepsTheFrozenBaseAmount() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.WRONG_ACCOUNT, OTHER_ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(crossCurrencyModel(OTHER_ACCOUNT));

    statementOnAccount();
    DockPrefill dock = service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG);

    assertThat(dock.input().baseAmount()).isEqualTo("3,10");
  }

  @Test
  void amendCarriesTheCrossCurrencyFieldsToTheCommit() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(ACCOUNT));
    when(dockCommitService.commitWithFundingAmount(any(), any())).thenReturn(OLD_TRANSACTION);
    when(ledgerService.findPostings(OLD_TRANSACTION))
        .thenReturn(
            List.of(
                new Posting(LEG, OLD_TRANSACTION, ACCOUNT, AMOUNT, null, "unreconciled", null)));
    DockInput typed =
        new DockInput(
            LocalDate.of(2026, 5, 20),
            "ShopAaa",
            8L,
            "Food",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            "CHF",
            "3,30",
            "3,40");

    service.amend(STATEMENT, LINE_ID, OLD_LEG, typed);

    ArgumentCaptor<DockEntry> entry = ArgumentCaptor.forClass(DockEntry.class);
    verify(dockCommitService).commitWithFundingAmount(entry.capture(), eq(AMOUNT));
    assertThat(entry.getValue().categoryCurrencyCode()).isEqualTo("CHF");
    assertThat(entry.getValue().categoryAmount()).isEqualTo("3,30");
    assertThat(entry.getValue().baseAmount()).isEqualTo("3,40");
  }

  @Test
  void amendRefusedByTheLedgerMatchesNothing() {
    statementOnAccount();
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(ACCOUNT));
    when(dockCommitService.commitWithFundingAmount(any(), any()))
        .thenThrow(new IllegalArgumentException("A CHF amount is required"));

    assertThatThrownBy(() -> service.amend(STATEMENT, LINE_ID, OLD_LEG, input(8L)))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("A CHF amount is required");

    verify(matchService, never()).link(anyLong(), anyLong(), anyLong());
  }

  @Test
  void amendRefusesPostingThatIsNotProposalForTheLine() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));

    assertThatThrownBy(() -> service.amend(STATEMENT, LINE_ID, OLD_LEG + 1, input(8L)))
        .isInstanceOf(StatementFormatException.class);

    verify(dockCommitService, never()).commitWithFundingAmount(any(), any());
  }

  @Test
  void amendRefusesAnExactProposalBecauseThoseAreAccepted() {
    StatementCandidate exact =
        new StatementCandidate(
            LINE_ID,
            OLD_LEG,
            OLD_TRANSACTION,
            ACCOUNT,
            "BankAaa-EUR",
            AMOUNT,
            null,
            null,
            false,
            "unreconciled",
            false,
            0);
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(
            new LineReview(
                line(), LineStatus.EXACT, null, List.of(new ProposedCandidate(exact, Tier.EXACT))));

    assertThatThrownBy(() -> service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void amendRefusesTransferWhoseFundingLegIsTheOtherAccount() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION)).thenReturn(bookedModel(OTHER_ACCOUNT));

    assertThatThrownBy(() -> service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG))
        .isInstanceOf(StatementFormatException.class)
        .isInstanceOf(RegisterOnlyException.class);
  }

  @Test
  void amendRefusesTransactionTheDockCannotEdit() {
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(proposal(Tier.AMOUNT_DIFFERS, ACCOUNT, false));
    when(dockEditService.load(OLD_TRANSACTION))
        .thenThrow(new IllegalArgumentException("cannot be edited in the dock yet"));

    assertThatThrownBy(() -> service.prefillAmend(STATEMENT, LINE_ID, OLD_LEG))
        .isInstanceOfSatisfying(
            RegisterOnlyException.class,
            e -> assertThat(e.transactionId()).isEqualTo(OLD_TRANSACTION));
  }
}
