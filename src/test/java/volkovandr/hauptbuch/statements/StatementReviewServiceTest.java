package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
  void checkRunsTheLiveReviewOfThatOneStatement() {
    Statement statement = statement(1L, START, END, null);
    when(statementService.get(1L)).thenReturn(statement);
    when(profileService.getIncludingDeleted(1L)).thenReturn(profile());
    when(lineRepository.findByStatement(1L)).thenReturn(List.of());

    StatementCheck check = service.check(1L);

    assertThat(check.green()).isFalse();
    assertThat(check.reason()).isEqualTo("No lines");
  }

  @Test
  void aGreenStatementHasNoProblemsAndNoReason() {
    StatementReview allMatched = new StatementReview(List.of(line(LineStatus.MATCHED)), List.of());

    StatementCheck check = StatementReviewService.check(allMatched, agreeing());

    assertThat(check.green()).isTrue();
    assertThat(check.reason()).isEmpty();
  }

  @Test
  void theReasonCountsTheLinesThatAreNotMatched() {
    StatementReview review =
        new StatementReview(
            List.of(line(LineStatus.MATCHED), line(LineStatus.MISSING), line(LineStatus.EXACT)),
            List.of());

    assertThat(StatementReviewService.check(review, agreeing()).reason())
        .isEqualTo("2 of 3 lines not matched");
  }

  @Test
  void theReasonCountsTheExtrasButNotTheBoundaryOnes() {
    StatementExtra extra =
        new StatementExtra(1L, 1L, START, "ShopAaa", null, new BigDecimal("-5.00"));
    StatementReview review =
        new StatementReview(
            List.of(line(LineStatus.MATCHED)),
            List.of(
                new ExtraReview(extra, ExtraReview.Boundary.NONE),
                new ExtraReview(extra, ExtraReview.Boundary.NEXT)));

    assertThat(StatementReviewService.check(review, agreeing()).reason()).isEqualTo("1 extra");
  }

  @Test
  void theReasonNamesEachBalanceThatIsOffAndByHowMuch() {
    StatementBalanceCheck off =
        new StatementBalanceCheck(
            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("1234.50"));
    StatementReview matched = new StatementReview(List.of(line(LineStatus.MATCHED)), List.of());

    assertThat(StatementReviewService.check(matched, new StatementBalances(off, off)).reason())
        .isEqualTo("Opening balance off by 1.234,50; Closing balance off by 1.234,50");
  }

  @Test
  void theReasonJoinsEveryProblem() {
    StatementBalanceCheck off =
        new StatementBalanceCheck(
            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("9"));
    StatementReview review = new StatementReview(List.of(line(LineStatus.MISSING)), List.of());

    assertThat(StatementReviewService.check(review, new StatementBalances(null, off)).reason())
        .isEqualTo("1 of 1 lines not matched; Closing balance off by 9,00");
  }

  private static StatementProfile profile() {
    return org.mockito.Mockito.mock(StatementProfile.class);
  }

  // ---- reviewLine (statements/issues/15) -------------------------------------------------

  private static StatementLine bookedLine(long id, int order, String date, String amount) {
    return new StatementLine(
        id,
        order,
        LocalDate.parse(date),
        null,
        new BigDecimal(amount),
        "x",
        null,
        null,
        "raw",
        null);
  }

  private static StatementCandidate candidate(
      long lineId, long postingId, String amount, boolean payeeSimilar, int distance) {
    return new StatementCandidate(
        lineId,
        postingId,
        postingId,
        ACCOUNT,
        "Own",
        new BigDecimal(amount),
        START,
        "ShopAaa",
        payeeSimilar,
        "unreconciled",
        false,
        distance);
  }

  private void givenStatementWithTwoLines() {
    when(statementService.get(1L)).thenReturn(statement(1L, START, END, "100.00"));
    when(profileService.getIncludingDeleted(1L)).thenReturn(profile());
    when(lineRepository.findByStatement(1L))
        .thenReturn(
            List.of(
                bookedLine(1L, 0, "2026-05-10", "-3.50"),
                bookedLine(2L, 1, "2026-05-11", "-3.50")));
  }

  @Test
  void reviewLineNeverRunsTheWholeStatementCandidateQuery() {
    givenStatementWithTwoLines();
    when(matchRepository.findCandidatesForLine(1L, 1L)).thenReturn(List.of());

    LineReview review = service.reviewLine(1L, 1L).orElseThrow();

    assertThat(review.line().statementLineId()).isEqualTo(1L);
    assertThat(review.status()).isEqualTo(LineStatus.MISSING);
    verify(matchRepository, never()).findCandidates(1L);
    verify(matchRepository, never()).findExtras(anyLong());
  }

  @Test
  void reviewLineYieldsLowerTierPostingToCloserRival() {
    givenStatementWithTwoLines();
    // posting 100 differs in amount from both lines; line 2 is one day closer, so it wins it
    StatementCandidate own = candidate(1L, 100L, "-3.80", true, 2);
    StatementCandidate rival = candidate(2L, 100L, "-3.80", true, 1);
    when(matchRepository.findCandidatesForLine(1L, 1L)).thenReturn(List.of(own));
    when(matchRepository.findCandidatesOnPostings(1L, 1L, List.of(100L)))
        .thenReturn(List.of(rival));

    LineReview review = service.reviewLine(1L, 1L).orElseThrow();

    assertThat(review.status()).isEqualTo(LineStatus.MISSING);
    assertThat(review.candidates()).isEmpty();
  }

  @Test
  void reviewLineFlagsSharedExactPostingAsCompeting() {
    givenStatementWithTwoLines();
    when(matchRepository.findCandidatesForLine(1L, 1L))
        .thenReturn(List.of(candidate(1L, 100L, "-3.50", true, 1)));
    when(matchRepository.findCandidatesOnPostings(1L, 1L, List.of(100L)))
        .thenReturn(List.of(candidate(2L, 100L, "-3.50", true, 1)));

    assertThat(service.reviewLine(1L, 1L).orElseThrow().status()).isEqualTo(LineStatus.COMPETING);
  }

  @Test
  void reviewLineIsEmptyForUnknownLine() {
    givenStatementWithTwoLines();

    assertThat(service.reviewLine(1L, 99L)).isEmpty();
    verify(matchRepository, never()).findCandidatesForLine(anyLong(), anyLong());
  }
}
