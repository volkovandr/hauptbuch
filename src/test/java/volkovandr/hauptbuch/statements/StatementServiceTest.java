package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Unit tier: {@link StatementService} orchestration (statements.md §3, §5) with the repositories
 * mocked — what an upload creates, how the period defaults, which accounts can hold a statement,
 * and that a grid save either applies every row or none.
 */
@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

  private static final long PROFILE_ID = 3L;
  private static final long ACCOUNT_ID = 11L;
  private static final String PATH = "2026/05/x.csv";

  @Mock private StatementRepository statementRepository;
  @Mock private StatementLineRepository lineRepository;
  @Mock private StatementProfileService profileService;
  @Mock private StatementCsvParser parser;
  @Mock private StatementStorage storage;
  @Mock private AccountService accountService;
  @Mock private PayingAccountDetector detector;

  private StatementService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementService(
            statementRepository,
            lineRepository,
            profileService,
            parser,
            storage,
            accountService,
            detector);
  }

  private static Account account(long id, String type, boolean personLeaf, LocalDate closedAt) {
    return new Account(
        id, "BankAaa-EUR", type, null, "EUR", null, null, closedAt, null, false, personLeaf, false);
  }

  private static StatementLine line(int order, LocalDate booking, String problem) {
    return new StatementLine(
        null,
        order,
        booking,
        null,
        new BigDecimal("-1.00"),
        "ShopAaa",
        "Card",
        null,
        "raw",
        problem);
  }

  private void openAccountExists() {
    when(accountService.findLiveByTypes(List.of("asset", "liability")))
        .thenReturn(
            List.of(
                account(ACCOUNT_ID, "asset", false, null),
                account(12L, "asset", true, null),
                account(13L, "asset", false, LocalDate.of(2025, 1, 1))));
  }

  @Test
  void createsTheStatementWithThePeriodFromTheFirstAndLastBookingDate() {
    openAccountExists();
    StatementProfile profile = StatementProfile.blankCsv();
    when(profileService.get(PROFILE_ID)).thenReturn(profile);
    when(storage.read(PATH)).thenReturn(new byte[] {1});
    List<StatementLine> lines =
        List.of(
            line(0, LocalDate.of(2026, 5, 9), null),
            line(1, null, "Unreadable booking date 'x'"),
            line(2, LocalDate.of(2026, 5, 2), null));
    when(parser.parse(eq(profile), any(), eq("EUR"), eq(Integer.MAX_VALUE)))
        .thenReturn(new CsvStatement(List.of(), lines, Set.of()));
    when(statementRepository.insert(any(Long.class), any(Long.class), any(), any(), any(), any()))
        .thenReturn(77L);

    long id = service.create(PROFILE_ID, PATH, "x.csv", ACCOUNT_ID);

    assertThat(id).isEqualTo(77L);
    verify(statementRepository)
        .insert(
            PROFILE_ID,
            ACCOUNT_ID,
            "x.csv",
            PATH,
            LocalDate.of(2026, 5, 2),
            LocalDate.of(2026, 5, 9));
    verify(lineRepository).insert(77L, lines.get(0));
    verify(lineRepository).insert(77L, lines.get(1));
    verify(lineRepository).insert(77L, lines.get(2));
  }

  @Test
  void refusesAccountThatCannotHoldStatement() {
    openAccountExists();

    for (long id : new long[] {12L, 13L, 99L}) {
      assertThatThrownBy(() -> service.create(PROFILE_ID, PATH, "x.csv", id))
          .isInstanceOf(StatementFormatException.class)
          .hasMessage("Choose the account this statement is for.");
    }
    verify(statementRepository, never())
        .insert(any(Long.class), any(Long.class), any(), any(), any(), any());
  }

  @Test
  void previewCountsLinesAndProblemsAndProposesTheAccount() {
    StatementProfile profile = StatementProfile.blankCsv();
    when(profileService.get(PROFILE_ID)).thenReturn(profile);
    when(storage.read(PATH)).thenReturn(new byte[] {1});
    when(parser.parse(eq(profile), any(), eq(null), eq(Integer.MAX_VALUE)))
        .thenReturn(
            new CsvStatement(
                List.of(),
                List.of(
                    line(0, LocalDate.of(2026, 5, 4), null),
                    line(1, LocalDate.of(2026, 5, 20), "Currency USD, but the account is in EUR")),
                Set.of("XX00 1111")));
    when(detector.detectByIdentifiers(Set.of("XX00 1111"))).thenReturn(OptionalLong.of(ACCOUNT_ID));

    UploadPreview preview = service.preview(PROFILE_ID, PATH);

    assertThat(preview.lineCount()).isEqualTo(2);
    assertThat(preview.problems()).containsExactly("Currency USD, but the account is in EUR");
    assertThat(preview.periodStart()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(preview.periodEnd()).isEqualTo(LocalDate.of(2026, 5, 20));
    assertThat(preview.proposedAccountId()).hasValue(ACCOUNT_ID);
  }

  @Test
  void updatesTheHeaderFromTypedValues() {
    stubLive(1L);

    service.updateHeader(1L, new HeaderEdit("2026-05-01", "2026-05-31", "1.000,50", ""));

    verify(statementRepository)
        .updateHeader(
            1L,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            new BigDecimal("1000.50"),
            null);
  }

  @Test
  void refusesReversedPeriodAndUnreadableBalances() {
    stubLive(1L);

    assertThatThrownBy(
            () -> service.updateHeader(1L, new HeaderEdit("2026-05-31", "2026-05-01", "", "")))
        .hasMessage("The period ends before it starts.");
    assertThatThrownBy(() -> service.updateHeader(1L, new HeaderEdit("", "", "abc", "")))
        .hasMessage("The opening balance 'abc' is not a number.");
    assertThatThrownBy(() -> service.updateHeader(1L, new HeaderEdit("May", "", "", "")))
        .hasMessage("The period start 'May' is not a date.");
    verify(statementRepository, never()).updateHeader(any(Long.class), any(), any(), any(), any());
  }

  @Test
  void savingTheGridClearsFixedProblemAndFlagsIncompleteLine() {
    stubLive(1L);
    when(lineRepository.findByStatement(1L))
        .thenReturn(
            List.of(
                withId(10L, line(0, null, "Unreadable booking date 'x'")),
                withId(11L, line(1, LocalDate.of(2026, 5, 2), null))));

    service.updateLines(
        1L,
        List.of(
            new LineEdit(10L, "2026-05-03", "", "-4,20", "ShopAaa", "Card", ""),
            new LineEdit(11L, "2026-05-02", "", "", "ShopBbb", "", "")));

    ArgumentCaptor<StatementLine> saved = ArgumentCaptor.forClass(StatementLine.class);
    verify(lineRepository, org.mockito.Mockito.times(2)).update(eq(1L), saved.capture());
    StatementLine fixed = saved.getAllValues().get(0);
    assertThat(fixed.problem()).isNull();
    assertThat(fixed.bookingDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(fixed.amount()).isEqualByComparingTo("-4.20");
    assertThat(fixed.rawText()).isEqualTo("raw");
    StatementLine incomplete = saved.getAllValues().get(1);
    assertThat(incomplete.problem()).isEqualTo("A line needs a booking date and an amount");
    assertThat(incomplete.description()).isNull();
  }

  @Test
  void foreignCurrencyProblemSurvivesEdit() {
    stubLive(1L);
    String problem = "Currency USD, but the account is in EUR";
    when(lineRepository.findByStatement(1L))
        .thenReturn(List.of(withId(10L, line(0, LocalDate.of(2026, 5, 2), problem))));

    service.updateLines(1L, List.of(new LineEdit(10L, "2026-05-02", "", "-1,00", "", "", "")));

    ArgumentCaptor<StatementLine> saved = ArgumentCaptor.forClass(StatementLine.class);
    verify(lineRepository).update(eq(1L), saved.capture());
    assertThat(saved.getValue().problem()).isEqualTo(problem);
  }

  @Test
  void oneUnreadableRowSavesNothing() {
    stubLive(1L);
    when(lineRepository.findByStatement(1L))
        .thenReturn(
            List.of(
                withId(10L, line(0, LocalDate.of(2026, 5, 2), null)),
                withId(11L, line(1, LocalDate.of(2026, 5, 2), null))));

    assertThatThrownBy(
            () ->
                service.updateLines(
                    1L,
                    List.of(
                        new LineEdit(10L, "2026-05-03", "", "-1,00", "", "", ""),
                        new LineEdit(11L, "2026-05-03", "", "lots", "", "", ""))))
        .hasMessage("Line 2: The amount 'lots' is not a number.");
    verify(lineRepository, never()).update(any(Long.class), any());
  }

  @Test
  void matchedLineKeepsItsDateAndAmountButTextStaysEditable() {
    stubLive(1L);
    when(lineRepository.findByStatement(1L))
        .thenReturn(List.of(withId(10L, line(0, LocalDate.of(2026, 5, 2), null))));
    when(lineRepository.isMatched(10L)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.updateLines(
                    1L, List.of(new LineEdit(10L, "2026-05-03", "", "-1,00", "", "", ""))))
        .hasMessage("A matched line keeps its date and amount. Unmatch it before changing them.");
    verify(lineRepository, never()).update(any(Long.class), any());

    service.updateLines(
        1L, List.of(new LineEdit(10L, "2026-05-02", "", "-1,00", "ShopAaa", "", "")));
    verify(lineRepository).update(eq(1L), any());
  }

  @Test
  void ignoresEditForLineOfAnotherStatement() {
    stubLive(1L);
    when(lineRepository.findByStatement(1L)).thenReturn(List.of());

    service.updateLines(1L, List.of(new LineEdit(99L, "2026-05-03", "", "-1,00", "", "", "")));

    verify(lineRepository, never()).update(any(Long.class), any());
  }

  @Test
  void deleteSoftDeletesTheStatement() {
    when(statementRepository.softDelete(1L)).thenReturn(1);

    service.delete(1L);

    verify(statementRepository).softDelete(1L);
  }

  @Test
  void lookupRefusesMissingOrDeletedStatement() {
    when(statementRepository.findById(5L)).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.get(5L))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("That statement no longer exists.");
  }

  @Test
  void accountNameSurvivesClosedAccountAndFallsBackWhenUnknown() {
    when(accountService.findById(ACCOUNT_ID))
        .thenReturn(
            java.util.Optional.of(account(ACCOUNT_ID, "asset", false, LocalDate.of(2025, 1, 1))));

    assertThat(service.accountName(ACCOUNT_ID)).isEqualTo("BankAaa-EUR");
    assertThat(service.accountName(99L)).isEqualTo("(unknown account)");
  }

  private void stubLive(long id) {
    when(statementRepository.findById(id))
        .thenReturn(
            java.util.Optional.of(
                new Statement(
                    id,
                    PROFILE_ID,
                    ACCOUNT_ID,
                    Statement.STATE_NEW,
                    "x.csv",
                    PATH,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  private static StatementLine withId(long id, StatementLine l) {
    return new StatementLine(
        id,
        l.sortOrder(),
        l.bookingDate(),
        l.valueDate(),
        l.amount(),
        l.counterparty(),
        l.description(),
        l.bankCategory(),
        l.rawText(),
        l.problem());
  }
}
