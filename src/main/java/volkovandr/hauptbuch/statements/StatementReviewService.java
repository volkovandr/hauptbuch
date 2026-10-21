package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * Matches a statement against the ledger on demand (statements.md §4.5): nothing is stored, so
 * reopening a statement simply re-runs the matcher and sees every edit made in the register since.
 */
@Service
public class StatementReviewService {

  private final StatementService statementService;
  private final StatementProfileService profileService;
  private final StatementLineRepository lineRepository;
  private final StatementMatchRepository matchRepository;

  StatementReviewService(
      StatementService statementService,
      StatementProfileService profileService,
      StatementLineRepository lineRepository,
      StatementMatchRepository matchRepository) {
    this.statementService = statementService;
    this.profileService = profileService;
    this.lineRepository = lineRepository;
    this.matchRepository = matchRepository;
  }

  /**
   * Review a live statement: every line's status and proposals, and the extras.
   *
   * @throws StatementFormatException when the statement no longer exists
   */
  public StatementReview review(long statementId) {
    Statement statement = statementService.get(statementId);
    StatementProfile profile = profileService.getIncludingDeleted(statement.statementProfileId());
    return matched(
        statement,
        profile,
        lineRepository.findByStatement(statementId),
        matchRepository.findCandidates(statementId),
        matchRepository.findExtras(statementId));
  }

  private StatementReview matched(
      Statement statement,
      StatementProfile profile,
      List<StatementLine> lines,
      List<StatementCandidate> candidates,
      List<StatementExtra> extras) {
    return StatementMatcher.review(
        lines,
        candidates,
        matchRepository.findMatches(statement.statementId()),
        extras,
        profile.windowDaysBefore(),
        profile.windowDaysAfter(),
        statement);
  }

  /**
   * Review one line of a live statement — the same {@link LineReview} {@link #review} gives it —
   * without matching the whole statement: its own candidates, plus the other lines' rows on those
   * same postings, because a posting goes to its best-fitting line (statements/issues/15).
   *
   * @return the line's review, or empty when the statement has no such line
   * @throws StatementFormatException when the statement no longer exists
   */
  public Optional<LineReview> reviewLine(long statementId, long statementLineId) {
    Statement statement = statementService.get(statementId);
    StatementProfile profile = profileService.getIncludingDeleted(statement.statementProfileId());
    List<StatementLine> lines = lineRepository.findByStatement(statementId);
    if (lines.stream().noneMatch(l -> l.statementLineId() == statementLineId)) {
      return Optional.empty();
    }
    List<StatementCandidate> candidates =
        new ArrayList<>(matchRepository.findCandidatesForLine(statementId, statementLineId));
    candidates.addAll(
        matchRepository.findCandidatesOnPostings(
            statementId,
            statementLineId,
            candidates.stream().map(StatementCandidate::postingId).distinct().toList()));
    return matched(statement, profile, lines, candidates, List.of()).lines().stream()
        .filter(l -> l.line().statementLineId() == statementLineId)
        .findFirst();
  }

  /**
   * The statement's two balance checks (statements.md §6.2); a check is absent when its bank
   * balance is, and both are when the statement has no period to read the ledger at.
   */
  public StatementBalances balances(long statementId, StatementReview review) {
    Statement statement = statementService.get(statementId);
    if (statement.periodStart() == null || statement.periodEnd() == null) {
      return new StatementBalances(null, null);
    }
    BigDecimal opening =
        matchRepository.ledgerBalance(
            statement.accountId(), StatementBalances.dayBefore(statement.periodStart()));
    BigDecimal closing =
        matchRepository.ledgerBalance(statement.accountId(), statement.periodEnd());
    return StatementBalances.of(
        statement, review, matchRepository.findNeighbourMatches(statementId), opening, closing);
  }

  /**
   * Which of these statements are green (statements.md §6.5): every line matched, no extras beyond
   * boundary extras, and both balance remainders at zero. A statement with no lines is not.
   */
  public Map<Long, Boolean> green(List<StatementRow> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(
                StatementRow::statementId, r -> isGreen(r.statementId()), (a, b) -> a));
  }

  /** The green rule (statements.md §6.5) over an already-computed review. */
  static boolean green(StatementReview review, StatementBalances balances) {
    return !review.lines().isEmpty()
        && review.matched() == review.lines().size()
        && review.extra() == 0
        && balances.agree();
  }

  private boolean isGreen(long statementId) {
    StatementReview review = review(statementId);
    return green(review, balances(statementId, review));
  }
}
