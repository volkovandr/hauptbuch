package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.shared.MoneyFormat;
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
   * Check one live statement against the green rule (statements.md §6.5): every line matched, no
   * extras beyond boundary extras, and both balance remainders at zero. Runs the full live review,
   * so the list page calls it per statement on request, never for every row.
   *
   * @throws StatementFormatException when the statement no longer exists
   */
  public StatementCheck check(long statementId) {
    StatementReview review = review(statementId);
    return check(review, balances(statementId, review));
  }

  /** The green rule (statements.md §6.5) over an already-computed review. */
  static StatementCheck check(StatementReview review, StatementBalances balances) {
    List<String> problems = new ArrayList<>();
    int lines = review.lines().size();
    if (lines == 0) {
      problems.add("No lines");
    } else if (review.matched() < lines) {
      problems.add((lines - review.matched()) + " of " + lines + " lines not matched");
    }
    if (review.extra() > 0) {
      problems.add(review.extra() + (review.extra() == 1 ? " extra" : " extras"));
    }
    addBalanceProblem(problems, "Opening", balances.opening());
    addBalanceProblem(problems, "Closing", balances.closing());
    return new StatementCheck(problems);
  }

  /** Whether the review is green (statements.md §6.5). */
  static boolean green(StatementReview review, StatementBalances balances) {
    return check(review, balances).green();
  }

  private static void addBalanceProblem(
      List<String> problems, String label, StatementBalanceCheck balance) {
    if (balance != null && !balance.agrees()) {
      problems.add(label + " balance off by " + MoneyFormat.number(balance.unexplained(), 2));
    }
  }
}
