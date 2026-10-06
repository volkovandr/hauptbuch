package volkovandr.hauptbuch.statements;

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
    return StatementMatcher.review(
        lineRepository.findByStatement(statementId),
        matchRepository.findCandidates(statementId),
        matchRepository.findMatches(statementId),
        matchRepository.findExtras(statementId),
        profile.windowDaysBefore(),
        profile.windowDaysAfter(),
        statement);
  }
}
