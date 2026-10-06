package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.statements.ProposedCandidate.Tier;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * Unit tier: the match actions (statements.md §5) with the repositories and {@code ledger} mocked —
 * what each action writes, and that every action is checked against the live review first.
 */
@ExtendWith(MockitoExtension.class)
class StatementMatchServiceTest {

  private static final long STATEMENT = 4L;
  private static final long LINE = 10L;
  private static final long POSTING = 50L;

  @Mock private StatementReviewService reviewService;
  @Mock private StatementService statementService;
  @Mock private StatementMatchRepository matchRepository;
  @Mock private LedgerService ledgerService;

  private StatementMatchService service;

  @BeforeEach
  void setUp() {
    service = new StatementMatchService(reviewService, statementService, matchRepository, ledgerService);
  }

  private static StatementLine line(long id) {
    return new StatementLine(
        id, 0, LocalDate.of(2026, 5, 20), null, new BigDecimal("-3.50"), "SHOPAAA", null, null, "raw", null);
  }

  private static ProposedCandidate proposal(long posting, Tier tier) {
    return new ProposedCandidate(
        new StatementCandidate(
            LINE, posting, posting, 1L, "BankAaa-EUR", new BigDecimal("-3.50"),
            LocalDate.of(2026, 5, 19), "ShopAaa", true, "unreconciled", false, 1),
        tier);
  }

  private static StatementMatch match(long line, long posting) {
    return new StatementMatch(
        line, posting, posting, LocalDate.of(2026, 5, 19), "ShopAaa", new BigDecimal("-3.50"), "reconciled");
  }

  private void reviewing(LineReview... lines) {
    when(reviewService.review(STATEMENT)).thenReturn(new StatementReview(List.of(lines), List.of()));
  }

  @Test
  void acceptRecordsTheMatchThenReconcilesTheLeg() {
    reviewing(new LineReview(line(LINE), LineStatus.EXACT, null, List.of(proposal(POSTING, Tier.EXACT))));

    service.accept(STATEMENT, LINE, POSTING);

    InOrder order = inOrder(matchRepository, ledgerService);
    order.verify(matchRepository).insertMatch(STATEMENT, LINE, POSTING);
    order.verify(ledgerService).markReconciled(List.of(POSTING));
  }

  @Test
  void acceptPicksOneOfSeveralAmbiguousCandidates() {
    reviewing(
        new LineReview(
            line(LINE),
            LineStatus.AMBIGUOUS,
            null,
            List.of(proposal(POSTING, Tier.EXACT), proposal(51L, Tier.EXACT))));

    service.accept(STATEMENT, LINE, 51L);

    verify(matchRepository).insertMatch(STATEMENT, LINE, 51L);
  }

  @Test
  void acceptRefusesAPostingThatIsNoLongerACandidate() {
    reviewing(new LineReview(line(LINE), LineStatus.EXACT, null, List.of(proposal(POSTING, Tier.EXACT))));

    assertThatThrownBy(() -> service.accept(STATEMENT, LINE, 99L))
        .isInstanceOf(StatementFormatException.class);
    verify(matchRepository, never()).insertMatch(anyLong(), anyLong(), anyLong());
  }

  @Test
  void acceptRefusesAmountDiffersAndWrongAccountCandidates() {
    reviewing(
        new LineReview(
            line(LINE),
            LineStatus.AMOUNT_DIFFERS,
            null,
            List.of(proposal(POSTING, Tier.AMOUNT_DIFFERS), proposal(51L, Tier.WRONG_ACCOUNT))));

    assertThatThrownBy(() -> service.accept(STATEMENT, LINE, POSTING))
        .isInstanceOf(StatementFormatException.class);
    assertThatThrownBy(() -> service.accept(STATEMENT, LINE, 51L))
        .isInstanceOf(StatementFormatException.class);
    verify(ledgerService, never()).markReconciled(anyCollection());
  }

  @Test
  void acceptRefusesAnAlreadyMatchedLine() {
    reviewing(
        new LineReview(line(LINE), LineStatus.MATCHED, match(LINE, POSTING), List.of(proposal(51L, Tier.EXACT))));

    assertThatThrownBy(() -> service.accept(STATEMENT, LINE, 51L))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void acceptAllExactMatchesOnlyUnambiguousLines() {
    reviewing(
        new LineReview(line(1L), LineStatus.EXACT, null, List.of(proposal(61L, Tier.EXACT))),
        new LineReview(
            line(2L), LineStatus.AMBIGUOUS, null, List.of(proposal(62L, Tier.EXACT), proposal(63L, Tier.EXACT))),
        new LineReview(line(3L), LineStatus.OVERLAP, null, List.of(proposal(64L, Tier.EXACT))),
        new LineReview(line(4L), LineStatus.EXACT, null, List.of(proposal(65L, Tier.EXACT))),
        new LineReview(line(5L), LineStatus.MISSING, null, List.of()));

    int matched = service.acceptAllExact(STATEMENT);

    org.assertj.core.api.Assertions.assertThat(matched).isEqualTo(2);
    verify(matchRepository).insertMatch(STATEMENT, 1L, 61L);
    verify(matchRepository).insertMatch(STATEMENT, 4L, 65L);
    verify(ledgerService).markReconciled(List.of(61L, 65L));
  }

  @Test
  void rejectCandidateRemembersTheDecision() {
    reviewing(new LineReview(line(LINE), LineStatus.OVERLAP, null, List.of(proposal(POSTING, Tier.EXACT))));

    service.rejectCandidate(STATEMENT, LINE, POSTING);

    verify(matchRepository).insertExclusion(LINE, POSTING);
  }

  @Test
  void unmatchRemovesTheMatchesOnThePostingAndUnreconcilesIt() {
    reviewing(new LineReview(line(LINE), LineStatus.MATCHED, match(LINE, POSTING), List.of()));

    service.unmatch(STATEMENT, LINE);

    InOrder order = inOrder(matchRepository, ledgerService);
    order.verify(matchRepository).deleteMatchesOnPostings(List.of(POSTING));
    order.verify(ledgerService).markUnreconciled(List.of(POSTING));
  }

  @Test
  void unmatchRefusesALineWithoutAMatch() {
    reviewing(new LineReview(line(LINE), LineStatus.MISSING, null, List.of()));

    assertThatThrownBy(() -> service.unmatch(STATEMENT, LINE))
        .isInstanceOf(StatementFormatException.class);
  }

  @Test
  void deleteKeepingReconciledLeavesThePostingsAlone() {
    when(matchRepository.deleteMatchesOfStatement(STATEMENT)).thenReturn(List.of(POSTING));

    service.deleteStatement(STATEMENT, false);

    verify(ledgerService, never()).markUnreconciled(anyCollection());
    verify(statementService).delete(STATEMENT);
  }

  @Test
  void deleteWithResetUnreconcilesOnlyPostingsNoStatementStillMatches() {
    when(matchRepository.deleteMatchesOfStatement(STATEMENT)).thenReturn(List.of(POSTING, 51L));
    when(matchRepository.postingsWithoutMatch(List.of(POSTING, 51L))).thenReturn(List.of(51L));

    service.deleteStatement(STATEMENT, true);

    verify(ledgerService).markUnreconciled(List.of(51L));
    verify(statementService).delete(STATEMENT);
  }
}
