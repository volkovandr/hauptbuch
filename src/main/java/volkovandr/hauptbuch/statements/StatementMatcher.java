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
 * rows the repository returned: a posting goes to the lines of its best tier — every line it is
 * exact for, otherwise the one with the closest booking date — so a line whose lower-tier candidate
 * went elsewhere falls to its next candidate, or to missing.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
final class StatementMatcher {

  private static final Comparator<ProposedCandidate> BEST_FIRST =
      Comparator.comparing(ProposedCandidate::tier)
          .thenComparingInt(p -> p.candidate().dayDistance())
          .thenComparingLong(p -> p.candidate().postingId());

  /** Booking date, undated lines last, then file order. */
  private static final Comparator<StatementLine> BY_BOOKING_DATE =
      Comparator.comparing(
              StatementLine::bookingDate, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparingInt(StatementLine::sortOrder);

  private StatementMatcher() {}

  /**
   * Review a statement.
   *
   * @param lines the statement's lines, in any order
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
    Set<Long> sharedPostings = sharedExactPostings(owned);
    List<LineReview> reviews =
        lines.stream()
            .sorted(BY_BOOKING_DATE)
            .map(
                line ->
                    reviewLine(
                        line,
                        matchByLine.get(line.statementLineId()),
                        owned.getOrDefault(line.statementLineId(), List.of()),
                        sharedPostings))
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

  /**
   * Each posting goes to the lines of its best tier: all of them for an exact posting (the date is
   * a weak signal, so the operator decides), the closest one for the lower tiers.
   */
  private static Map<Long, List<ProposedCandidate>> assign(
      List<StatementLine> lines, List<StatementCandidate> candidates, Statement statement) {
    Map<Long, StatementLine> byId = new HashMap<>();
    lines.forEach(l -> byId.put(l.statementLineId(), l));
    Map<Long, List<ProposedCandidate>> claimsByPosting = new HashMap<>();
    for (StatementCandidate candidate : candidates) {
      Tier tier = tierOf(candidate, byId.get(candidate.statementLineId()), statement);
      if (tier != null) {
        claim(claimsByPosting, candidate, tier);
      }
    }
    Map<Long, List<ProposedCandidate>> byLine = new HashMap<>();
    claimsByPosting
        .values()
        .forEach(
            claims ->
                winners(claims, byId)
                    .forEach(
                        p ->
                            byLine
                                .computeIfAbsent(
                                    p.candidate().statementLineId(), k -> new ArrayList<>())
                                .add(p)));
    byLine.values().forEach(list -> list.sort(BEST_FIRST));
    return byLine;
  }

  private static void claim(
      Map<Long, List<ProposedCandidate>> claimsByPosting, StatementCandidate candidate, Tier tier) {
    claimsByPosting
        .computeIfAbsent(candidate.postingId(), k -> new ArrayList<>())
        .add(new ProposedCandidate(candidate, tier));
  }

  private static List<ProposedCandidate> winners(
      List<ProposedCandidate> claims, Map<Long, StatementLine> lines) {
    ProposedCandidate best =
        claims.stream()
            .min(
                Comparator.comparing(ProposedCandidate::tier)
                    .thenComparingInt(p -> p.candidate().dayDistance())
                    .thenComparingInt(p -> lines.get(p.candidate().statementLineId()).sortOrder()))
            .orElseThrow();
    if (best.tier() == Tier.EXACT) {
      return claims.stream().filter(p -> p.tier() == Tier.EXACT).toList();
    }
    return List.of(best);
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

  /** The postings that are an exact proposal of more than one line. */
  private static Set<Long> sharedExactPostings(Map<Long, List<ProposedCandidate>> owned) {
    return owned.values().stream()
        .flatMap(List::stream)
        .filter(p -> p.tier() == Tier.EXACT)
        .collect(Collectors.groupingBy(p -> p.candidate().postingId(), Collectors.counting()))
        .entrySet()
        .stream()
        .filter(e -> e.getValue() > 1)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  private static LineReview reviewLine(
      StatementLine line,
      StatementMatch match,
      List<ProposedCandidate> proposals,
      Set<Long> sharedPostings) {
    if (match != null) {
      return new LineReview(line, LineStatus.MATCHED, match, List.of());
    }
    if (line.problem() != null) {
      return new LineReview(line, LineStatus.PROBLEM, null, List.of());
    }
    return new LineReview(line, statusOf(proposals, sharedPostings), null, proposals);
  }

  private static LineStatus statusOf(List<ProposedCandidate> proposals, Set<Long> sharedPostings) {
    List<ProposedCandidate> exact = proposals.stream().filter(p -> p.tier() == Tier.EXACT).toList();
    if (exact.size() > 1) {
      return LineStatus.AMBIGUOUS;
    }
    if (exact.size() == 1) {
      StatementCandidate only = exact.get(0).candidate();
      if (only.matchedElsewhere()) {
        return LineStatus.OVERLAP;
      }
      return sharedPostings.contains(only.postingId()) ? LineStatus.COMPETING : LineStatus.EXACT;
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
    if (s.periodStart() != null && date.isBefore(s.periodStart().plusDays(windowDaysAfter))) {
      return Boundary.PREVIOUS;
    }
    return Boundary.NONE;
  }
}
