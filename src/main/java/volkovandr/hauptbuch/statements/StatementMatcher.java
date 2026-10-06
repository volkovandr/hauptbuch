package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import volkovandr.hauptbuch.statements.ExtraReview.Boundary;
import volkovandr.hauptbuch.statements.ProposedCandidate.Tier;

/**
 * Sorts the SQL candidates into tiers and statuses (statements.md §4.2, §4.4). Pure logic over the
 * rows the repository returned: a posting is proposed to at most one line — the one with the
 * best tier, then the closest booking date — so a line whose candidate went elsewhere falls to its
 * next candidate, or to missing.
 */
final class StatementMatcher {

  private static final Comparator<ProposedCandidate> BEST_FIRST =
      Comparator.comparing(ProposedCandidate::tier)
          .thenComparingInt(p -> p.candidate().dayDistance())
          .thenComparingLong(p -> p.candidate().postingId());

  private StatementMatcher() {}

  /**
   * Review a statement.
   *
   * @param lines the statement's lines in file order
   * @param candidates the window candidates of every unmatched, readable line
   * @param matches the statement's confirmed matches
   * @param extras the unreconciled, unmatched legs dated in the period
   * @param windowDaysBefore the profile's days a posting may precede its booking (boundary labels)
   * @param windowDaysAfter the profile's days a posting may follow its booking (boundary labels)
   * @param statement the statement, for its account and period
   */
  static StatementReview review(
      List<StatementLine> lines,
      List<StatementCandidate> candidates,
      List<StatementMatch> matches,
      List<StatementExtra> extras,
      int windowDaysBefore,
      int windowDaysAfter,
      Statement statement) {
    Map<Long, StatementMatch> matchByLine = new HashMap<>();
    matches.forEach(m -> matchByLine.put(m.statementLineId(), m));
    Map<Long, List<ProposedCandidate>> owned = assign(lines, candidates, statement);
    List<LineReview> reviews =
        lines.stream()
            .map(
                line ->
                    reviewLine(
                        line,
                        matchByLine.get(line.statementLineId()),
                        owned.getOrDefault(line.statementLineId(), List.of())))
            .toList();
    Set<Long> proposedPostings =
        owned.values().stream()
            .flatMap(List::stream)
            .map(p -> p.candidate().postingId())
            .collect(Collectors.toSet());
    List<ExtraReview> extraReviews =
        extras.stream()
            .filter(e -> !proposedPostings.contains(e.postingId()))
            .map(e -> new ExtraReview(e, boundary(e, windowDaysBefore, windowDaysAfter, statement)))
            .toList();
    return new StatementReview(reviews, extraReviews);
  }

  /** Each posting goes to exactly one line: best tier, then closest date, then file order. */
  private static Map<Long, List<ProposedCandidate>> assign(
      List<StatementLine> lines, List<StatementCandidate> candidates, Statement statement) {
    Map<Long, StatementLine> byId = new HashMap<>();
    lines.forEach(l -> byId.put(l.statementLineId(), l));
    Map<Long, ProposedCandidate> owner = new HashMap<>();
    for (StatementCandidate candidate : candidates) {
      Tier tier = tierOf(candidate, byId.get(candidate.statementLineId()), statement);
      if (tier != null) {
        ProposedCandidate proposed = new ProposedCandidate(candidate, tier);
        owner.merge(
            candidate.postingId(),
            proposed,
            (current, challenger) ->
                wins(challenger, current, byId) ? challenger : current);
      }
    }
    Map<Long, List<ProposedCandidate>> byLine = new HashMap<>();
    owner
        .values()
        .forEach(
            p ->
                byLine
                    .computeIfAbsent(p.candidate().statementLineId(), k -> new ArrayList<>())
                    .add(p));
    byLine.values().forEach(list -> list.sort(BEST_FIRST));
    return byLine;
  }

  private static boolean wins(
      ProposedCandidate challenger, ProposedCandidate current, Map<Long, StatementLine> lines) {
    int byTier = challenger.tier().compareTo(current.tier());
    if (byTier != 0) {
      return byTier < 0;
    }
    int byDistance =
        Integer.compare(challenger.candidate().dayDistance(), current.candidate().dayDistance());
    if (byDistance != 0) {
      return byDistance < 0;
    }
    return lines.get(challenger.candidate().statementLineId()).sortOrder()
        < lines.get(current.candidate().statementLineId()).sortOrder();
  }

  private static Tier tierOf(
      StatementCandidate candidate, StatementLine line, Statement statement) {
    boolean sameAmount = candidate.amount().compareTo(line.amount()) == 0;
    if (candidate.accountId() == statement.accountId()) {
      if (sameAmount) {
        return Tier.EXACT;
      }
      return candidate.payeeSimilar() ? Tier.AMOUNT_DIFFERS : null;
    }
    return sameAmount && candidate.payeeSimilar() ? Tier.WRONG_ACCOUNT : null;
  }

  private static LineReview reviewLine(
      StatementLine line, StatementMatch match, List<ProposedCandidate> proposals) {
    if (match != null) {
      return new LineReview(line, LineStatus.MATCHED, match, List.of());
    }
    if (line.problem() != null) {
      return new LineReview(line, LineStatus.PROBLEM, null, List.of());
    }
    return new LineReview(line, statusOf(proposals), null, proposals);
  }

  private static LineStatus statusOf(List<ProposedCandidate> proposals) {
    List<ProposedCandidate> exact = proposals.stream().filter(p -> p.tier() == Tier.EXACT).toList();
    if (exact.size() > 1) {
      return LineStatus.AMBIGUOUS;
    }
    if (exact.size() == 1) {
      return exact.get(0).candidate().matchedElsewhere() ? LineStatus.OVERLAP : LineStatus.EXACT;
    }
    if (proposals.stream().anyMatch(p -> p.tier() == Tier.AMOUNT_DIFFERS)) {
      return LineStatus.AMOUNT_DIFFERS;
    }
    return proposals.isEmpty() ? LineStatus.MISSING : LineStatus.WRONG_ACCOUNT;
  }

  private static Boundary boundary(
      StatementExtra extra, int windowDaysBefore, int windowDaysAfter, Statement s) {
    LocalDate date = extra.transactionDate();
    if (s.periodEnd() != null && date.isAfter(s.periodEnd().minusDays(windowDaysBefore))) {
      return Boundary.NEXT;
    }
    if (s.periodStart() != null
        && date.isBefore(s.periodStart().plusDays(windowDaysAfter))) {
      return Boundary.PREVIOUS;
    }
    return Boundary.NONE;
  }
}
