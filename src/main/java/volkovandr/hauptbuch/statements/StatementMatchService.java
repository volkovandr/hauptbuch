package volkovandr.hauptbuch.statements;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.statements.ProposedCandidate.Tier;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * The match actions of the statement page (statements.md §5, plan slice c2). Every action is
 * checked against the live review, never against what the page showed when it was rendered — a
 * proposal the ledger has since changed is refused rather than applied. A confirmed match makes the
 * posting {@code reconciled} through {@code ledger}; only equal-amount candidates on the
 * statement's own account are accepted here, the rest go through the dock (slice d).
 */
@Service
public class StatementMatchService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementMatchService.class);
  private static final String STALE =
      "That proposal is no longer available. The page was reloaded.";

  private final StatementReviewService reviewService;
  private final StatementService statementService;
  private final StatementMatchRepository matchRepository;
  private final LedgerService ledgerService;

  StatementMatchService(
      StatementReviewService reviewService,
      StatementService statementService,
      StatementMatchRepository matchRepository,
      LedgerService ledgerService) {
    this.reviewService = reviewService;
    this.statementService = statementService;
    this.matchRepository = matchRepository;
    this.ledgerService = ledgerService;
  }

  /**
   * Confirm one candidate for a line: the "Accept" of an exact proposal, the pick from an ambiguous
   * list, and "the same bank movement" of an overlap.
   *
   * @throws StatementFormatException when the line is already matched or the posting is not an
   *     equal-amount candidate on the statement's account
   */
  @Transactional
  public void accept(long statementId, long statementLineId, long postingId) {
    LineReview line = lineOf(statementId, statementLineId);
    boolean offered =
        line.status() != LineStatus.MATCHED
            && line.candidates().stream()
                .anyMatch(p -> p.tier() == Tier.EXACT && p.candidate().postingId() == postingId);
    if (!offered) {
      throw new StatementFormatException(STALE);
    }
    confirm(statementId, List.of(new Pair(statementLineId, postingId)));
  }

  /**
   * Confirm every unambiguous exact proposal at once (statements.md §6.3). Ambiguous lines and
   * overlaps wait for the operator.
   *
   * @return how many lines were matched
   */
  @Transactional
  public int acceptAllExact(long statementId) {
    List<Pair> pairs = new ArrayList<>();
    for (LineReview line : reviewService.review(statementId).lines()) {
      if (line.status() == LineStatus.EXACT) {
        pairs.add(
            new Pair(
                line.line().statementLineId(), line.candidates().get(0).candidate().postingId()));
      }
    }
    confirm(statementId, pairs);
    return pairs.size();
  }

  /**
   * "A different transaction that looks the same": the line's overlapping candidate is not its
   * movement, so it is never proposed to this line again and the line becomes missing or falls to
   * its next candidate (statements.md §4.4).
   */
  @Transactional
  public void rejectCandidate(long statementId, long statementLineId, long postingId) {
    LineReview line = lineOf(statementId, statementLineId);
    boolean offered =
        line.status() != LineStatus.MATCHED
            && line.candidates().stream().anyMatch(p -> p.candidate().postingId() == postingId);
    if (!offered) {
      throw new StatementFormatException(STALE);
    }
    matchRepository.insertExclusion(statementLineId, postingId);
  }

  /**
   * Unmatch a line: its match goes — and with it every match on that posting, on any statement,
   * since a match needs a {@code reconciled} leg — and the posting becomes {@code unreconciled}
   * (statements.md §5), even a Money-imported {@code R}.
   */
  @Transactional
  public void unmatch(long statementId, long statementLineId) {
    StatementMatch match = lineOf(statementId, statementLineId).match();
    if (match == null) {
      throw new StatementFormatException("That line has no match to remove.");
    }
    matchRepository.deleteMatchesOnPostings(List.of(match.postingId()));
    ledgerService.markUnreconciled(List.of(match.postingId()));
    LOG.debug("Unmatched line {} of statement {}", statementLineId, statementId);
  }

  /**
   * Delete a statement: its matches go, its postings stay {@code reconciled} or — when {@code
   * resetReconciliation} — go back to {@code unreconciled}, except a posting another statement
   * still matches (statements.md §5). The file stays on the Pi.
   */
  @Transactional
  public void deleteStatement(long statementId, boolean resetReconciliation) {
    statementService.get(statementId);
    List<Long> postings = matchRepository.deleteMatchesOfStatement(statementId);
    if (resetReconciliation) {
      ledgerService.markUnreconciled(matchRepository.postingsWithoutMatch(postings));
    }
    statementService.delete(statementId);
  }

  private LineReview lineOf(long statementId, long statementLineId) {
    return reviewService.review(statementId).lines().stream()
        .filter(l -> l.line().statementLineId() == statementLineId)
        .findFirst()
        .orElseThrow(() -> new StatementFormatException(STALE));
  }

  private void confirm(long statementId, List<Pair> pairs) {
    pairs.forEach(p -> matchRepository.insertMatch(statementId, p.lineId(), p.postingId()));
    ledgerService.markReconciled(pairs.stream().map(Pair::postingId).toList());
    LOG.debug("Matched {} lines of statement {}", pairs.size(), statementId);
  }

  private record Pair(long lineId, long postingId) {}
}
