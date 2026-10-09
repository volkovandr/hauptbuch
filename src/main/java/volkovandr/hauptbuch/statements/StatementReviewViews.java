package volkovandr.hauptbuch.statements;

import static volkovandr.hauptbuch.statements.StatementController.date;
import static volkovandr.hauptbuch.statements.StatementController.number;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** What the statement page's matching table shows, formatted for the template. */
final class StatementReviewViews {

  private StatementReviewViews() {}

  /**
   * A line as the matcher sees it: status, and a one-line account of its match or proposals. {@code
   * differentPosting} is the overlapping posting the "Different transaction" answer refers to, or
   * null when the line is not an overlap.
   */
  record ReviewLineView(
      long lineId,
      String bookingDate,
      String amount,
      String text,
      String status,
      String statusClass,
      List<String> details,
      List<Pick> picks,
      List<Pick> fixes,
      boolean canUnmatch,
      Long differentPosting,
      boolean canCreate) {

    static ReviewLineView of(LineReview review) {
      StatementLine line = review.line();
      boolean overlap = review.status() == LineStatus.OVERLAP;
      return new ReviewLineView(
          line.statementLineId(),
          date(line.bookingDate()),
          number(line.amount()),
          text(line),
          review.status().label(),
          "statement-status--" + review.status().name().toLowerCase(Locale.ROOT),
          details(review),
          picks(review),
          fixes(review),
          review.match() != null,
          overlap ? review.firstProposedPostingId() : null,
          review.status() == LineStatus.MISSING);
    }

    /** The equal-amount candidates the operator can confirm here; the rest need the dock. */
    private static List<Pick> picks(LineReview review) {
      return review.candidates().stream()
          .filter(p -> p.tier() == ProposedCandidate.Tier.EXACT)
          .map(p -> new Pick(p.candidate().postingId(), pickLabel(review.status(), p)))
          .toList();
    }

    /** The other proposals, which need the dock: an amount to correct or a leg to move here. */
    private static List<Pick> fixes(LineReview review) {
      if (review.status() == LineStatus.MATCHED) {
        return List.of();
      }
      return review.candidates().stream()
          .filter(p -> p.tier() != ProposedCandidate.Tier.EXACT)
          .map(p -> new Pick(p.candidate().postingId(), fixLabel(p)))
          .toList();
    }

    private static String fixLabel(ProposedCandidate proposed) {
      return proposed.tier() == ProposedCandidate.Tier.WRONG_ACCOUNT ? "Move here" : "Amend";
    }

    private static String pickLabel(LineStatus status, ProposedCandidate proposed) {
      if (status == LineStatus.OVERLAP) {
        return "Same movement";
      }
      if (status.hasSingleProposal()) {
        return "Accept";
      }
      return "Pick " + candidateText(proposed);
    }

    private static String text(StatementLine line) {
      return Stream.of(line.counterparty(), line.description())
          .filter(t -> t != null && !t.isBlank())
          .collect(Collectors.joining(" · "));
    }

    private static List<String> details(LineReview review) {
      if (review.match() != null) {
        StatementMatch m = review.match();
        return List.of(describe(m.transactionDate(), m.payeeName(), m.amount(), null));
      }
      if (review.line().problem() != null) {
        return List.of(review.line().problem());
      }
      return review.candidates().stream().map(ReviewLineView::candidateText).toList();
    }

    private static String candidateText(ProposedCandidate proposed) {
      StatementCandidate c = proposed.candidate();
      return StatementReviewViews.describe(
          c.transactionDate(), c.payeeName(), c.amount(), suffix(proposed));
    }
  }

  /** A candidate to confirm, with the text of its button. */
  record Pick(long postingId, String label) {}

  /** An extra, with its boundary label. */
  record ExtraView(
      long postingId, String date, String payee, String note, String amount, String boundary) {

    static ExtraView of(ExtraReview review) {
      StatementExtra e = review.extra();
      return new ExtraView(
          e.postingId(),
          StatementController.date(e.transactionDate()),
          e.payeeName() == null ? "" : e.payeeName(),
          e.note() == null ? "" : e.note(),
          number(e.amount()),
          review.boundary().label());
    }
  }

  private static String suffix(ProposedCandidate proposed) {
    if (proposed.tier() == ProposedCandidate.Tier.WRONG_ACCOUNT) {
      return "on " + proposed.candidate().accountName();
    }
    return proposed.candidate().matchedElsewhere() ? "(matched on another statement)" : null;
  }

  static String describe(LocalDate date, String payee, BigDecimal amount, String suffix) {
    String text = date(date) + " " + (payee == null ? "(no payee)" : payee) + " " + number(amount);
    return suffix == null ? text : text + " " + suffix;
  }
}
