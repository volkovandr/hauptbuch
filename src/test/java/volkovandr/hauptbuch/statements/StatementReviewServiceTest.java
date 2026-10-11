package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * Unit tier: the balance checks' orchestration (statements.md §6.2) and the green rule (§6.5) with
 * the repositories mocked — which dates the ledger is read at, and when no check is made at all.
 */
@ExtendWith(MockitoExtension.class)
class StatementReviewServiceTest {

  private static final long ACCOUNT = 7L;
  private static final LocalDate START = LocalDate.of(2026, 5, 1);
  private static final LocalDate END = LocalDate.of(2026, 5, 31);

  @Mock private StatementService statementService;
  @Mock private StatementProfileService profileService;
  @Mock private StatementLineRepository lineRepository;
  @Mock private StatementMatchRepository matchRepository;

  private StatementReviewService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementReviewService(
            statementService, profileService, lineRepository, matchRepository);
  }

  private static Statement statement(long id, LocalDate start, LocalDate end, String opening) {
    return new Statement(
        id,
        1L,
        ACCOUNT,
        Statement.STATE_NEW,
        "may.pdf",
        "x",
        start,
        end,
        opening == null ? null : new BigDecimal(opening),
        opening == null ? null : new BigDecimal("80.00"),
        null,
        null);
  }

  private static LineReview line(LineStatus status) {
    StatementLine line =
        new StatementLine(
            1L, 1, START, null, new BigDecimal("-20.00"), "x", null, null, "raw", null);
    StatementMatch match =
        status == LineStatus.MATCHED
            ? new StatementMatch(
                1L, 1L, 1L, START, "ShopAaa", new BigDecimal("-20.00"), "reconciled")
            : null;
    return new LineReview(line, status, match, List.of());
  }

  private static StatementBalances agreeing() {
    StatementBalanceCheck ok =
        new StatementBalanceCheck(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
    return new StatementBalances(ok, ok);
  }

  @Test
  void readsTheLedgerTheDayBeforeThePeriodAndAtItsEnd() {
    Statement statement = statement(1L, START, END, "100.00");
    when(statementService.get(1L)).thenReturn(statement);
    when(matchRepository.ledgerBalance(ACCOUNT, START.minusDays(1)))
        .thenReturn(new BigDecimal("100.00"));
    when(matchRepository.ledgerBalance(ACCOUNT, END)).thenReturn(new BigDecimal("80.00"));

    StatementBalances balances =
        service.balances(1L, new StatementReview(List.of(line(LineStatus.MATCHED)), List.of()));

    assertThat(balances.opening().ledger()).isEqualByComparingTo("100.00");
    assertThat(balances.closing().ledger()).isEqualByComparingTo("80.00");
    assertThat(balances.agree()).isTrue();
  }

  @Test
  void makesNoCheckAndNeverReadsTheLedgerWithoutPeriod() {
    when(statementService.get(1L)).thenReturn(statement(1L, null, END, "100.00"));

    StatementBalances balances = service.balances(1L, new StatementReview(List.of(), List.of()));

    assertThat(balances.opening()).isNull();
    assertThat(balances.closing()).isNull();
    verify(matchRepository, never()).ledgerBalance(anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void greenNeedsEveryLineMatchedNoExtrasAndAgreeingBalances() {
    StatementReview allMatched = new StatementReview(List.of(line(LineStatus.MATCHED)), List.of());

    assertThat(StatementReviewService.green(allMatched, agreeing())).isTrue();
  }

  @Test
  void statementWithoutLinesIsNotGreen() {
    assertThat(StatementReviewService.green(new StatementReview(List.of(), List.of()), agreeing()))
        .isFalse();
  }

  @Test
  void anUnmatchedLineOrDisagreeingBalanceIsNotGreen() {
    StatementBalanceCheck off =
        new StatementBalanceCheck(
            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("9"));

    assertThat(
            StatementReviewService.green(
                new StatementReview(List.of(line(LineStatus.MISSING)), List.of()), agreeing()))
        .isFalse();
    assertThat(
            StatementReviewService.green(
                new StatementReview(List.of(line(LineStatus.MATCHED)), List.of()),
                new StatementBalances(off, null)))
        .isFalse();
  }

  @Test
  void greenForTheListIsKeyedByStatementAndRunsTheLiveReview() {
    Statement statement = statement(1L, START, END, null);
    when(statementService.get(1L)).thenReturn(statement);
    when(profileService.getIncludingDeleted(1L)).thenReturn(profile());
    when(lineRepository.findByStatement(1L)).thenReturn(List.of());

    Map<Long, Boolean> green =
        service.green(
            List.of(new StatementRow(1L, "BankAaa-EUR", "p", "may.pdf", START, END, 0, 0)));

    assertThat(green).containsExactly(Map.entry(1L, false));
  }

  private static StatementProfile profile() {
    return org.mockito.Mockito.mock(StatementProfile.class);
  }
}
