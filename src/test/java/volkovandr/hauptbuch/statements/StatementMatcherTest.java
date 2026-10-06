package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.statements.ExtraReview.Boundary;

/**
 * Unit tier: {@link StatementMatcher} (statements.md §4.2, §4.4, §6.3) — how the SQL candidates
 * become statuses, that a posting is proposed to one line only, and the boundary labels.
 */
class StatementMatcherTest {

  private static final long OWN = 1L;
  private static final long OTHER = 2L;
  private static final Statement STATEMENT =
      new Statement(
          9L,
          1L,
          OWN,
          Statement.STATE_NEW,
          "may.csv",
          "x",
          LocalDate.of(2026, 5, 1),
          LocalDate.of(2026, 5, 31),
          null,
          null,
          null,
          null);

  private static StatementLine line(long id, int order, String date, String amount) {
    return new StatementLine(
        id,
        order,
        LocalDate.parse(date),
        null,
        new BigDecimal(amount),
        "SHOPAAA 4711",
        null,
        null,
        "raw",
        null);
  }

  private static StatementCandidate candidate(
      long lineId, long postingId, long accountId, String amount, int distance, boolean similar) {
    return new StatementCandidate(
        lineId,
        postingId,
        postingId,
        accountId,
        "Account" + accountId,
        new BigDecimal(amount),
        LocalDate.of(2026, 5, 10),
        "ShopAaa",
        similar,
        "unreconciled",
        false,
        distance);
  }

  private static StatementReview review(
      List<StatementLine> lines,
      List<StatementCandidate> candidates,
      List<StatementMatch> matches,
      List<StatementExtra> extras) {
    return StatementMatcher.review(lines, candidates, matches, extras, 10, 3, STATEMENT);
  }

  private static LineStatus statusOf(StatementReview review, int index) {
    return review.lines().get(index).status();
  }

  @Test
  void linesAreOrderedByBookingDateThenFileOrderWithUndatedLast() {
    StatementLine undated =
        new StatementLine(4L, 0, null, null, null, "unreadable", null, "bad date", "raw", null);

    StatementReview result =
        review(
            List.of(
                line(1, 1, "2026-05-20", "-1.00"),
                undated,
                line(2, 2, "2026-05-03", "-2.00"),
                line(3, 3, "2026-05-03", "-3.00")),
            List.of(),
            List.of(),
            List.of());

    assertThat(result.lines())
        .extracting(r -> r.line().statementLineId())
        .containsExactly(2L, 3L, 1L, 4L);
  }

  @Test
  void singleEqualAmountOnTheStatementAccountIsExact() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50")),
            List.of(candidate(1, 100, OWN, "-3.50", 2, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.EXACT);
    assertThat(result.lines().get(0).candidates()).hasSize(1);
  }

  @Test
  void twoEqualAmountCandidatesAreAmbiguous() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50")),
            List.of(
                candidate(1, 100, OWN, "-3.50", 2, false),
                candidate(1, 101, OWN, "-3.50", 3, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.AMBIGUOUS);
  }

  @Test
  void differentAmountNeedsSimilarPayee() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50"), line(2, 1, "2026-05-20", "-8.00")),
            List.of(
                candidate(1, 100, OWN, "-3.80", 2, true),
                candidate(2, 101, OWN, "-9.00", 1, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.AMOUNT_DIFFERS);
    assertThat(statusOf(result, 1)).isEqualTo(LineStatus.MISSING);
  }

  @Test
  void wrongAccountCandidateNeedsEqualAmountAndSimilarPayee() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50"), line(2, 1, "2026-05-20", "-8.00")),
            List.of(
                candidate(1, 100, OTHER, "-3.50", 2, true),
                candidate(2, 101, OTHER, "-8.00", 1, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.WRONG_ACCOUNT);
    assertThat(statusOf(result, 1)).isEqualTo(LineStatus.MISSING);
  }

  @Test
  void exactCandidateBeatsWrongAccountOne() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50")),
            List.of(
                candidate(1, 100, OTHER, "-3.50", 0, true),
                candidate(1, 101, OWN, "-3.50", 3, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.EXACT);
    assertThat(result.lines().get(0).candidates())
        .extracting(p -> p.candidate().postingId())
        .containsExactly(101L, 100L);
  }

  @Test
  void postingIsProposedToTheLineWithTheClosestBookingDate() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50"), line(2, 1, "2026-05-14", "-3.50")),
            List.of(
                candidate(1, 100, OWN, "-3.50", 3, false),
                candidate(2, 100, OWN, "-3.50", 1, false),
                candidate(1, 101, OWN, "-3.50", 2, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.EXACT);
    assertThat(result.lines().get(0).candidates().get(0).candidate().postingId()).isEqualTo(101L);
    assertThat(result.lines().get(1).candidates().get(0).candidate().postingId()).isEqualTo(100L);
  }

  @Test
  void lineWhoseOnlyCandidateWentToAnotherLineIsMissing() {
    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50"), line(2, 1, "2026-05-14", "-3.50")),
            List.of(
                candidate(1, 100, OWN, "-3.50", 3, false),
                candidate(2, 100, OWN, "-3.50", 1, false)),
            List.of(),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.MISSING);
    assertThat(statusOf(result, 1)).isEqualTo(LineStatus.EXACT);
  }

  @Test
  void anExactCandidateMatchedOnAnotherStatementIsAnOverlap() {
    StatementCandidate taken =
        new StatementCandidate(
            1,
            100,
            100,
            OWN,
            "Own",
            new BigDecimal("-3.50"),
            LocalDate.of(2026, 5, 10),
            null,
            false,
            "reconciled",
            true,
            2);

    StatementReview result =
        review(List.of(line(1, 0, "2026-05-12", "-3.50")), List.of(taken), List.of(), List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.OVERLAP);
  }

  @Test
  void matchedAndProblemLinesAreNeverProposed() {
    StatementLine broken =
        new StatementLine(2L, 1, null, null, null, null, null, null, "raw", "Unreadable date");
    StatementMatch match =
        new StatementMatch(
            1,
            100,
            100,
            LocalDate.of(2026, 5, 10),
            "ShopAaa",
            new BigDecimal("-3.50"),
            "reconciled");

    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50"), broken),
            List.of(),
            List.of(match),
            List.of());

    assertThat(statusOf(result, 0)).isEqualTo(LineStatus.MATCHED);
    assertThat(statusOf(result, 1)).isEqualTo(LineStatus.PROBLEM);
    assertThat(result.matched()).isEqualTo(1);
    assertThat(result.reconciled()).isEqualTo(1);
  }

  @Test
  void extrasNearThePeriodEdgesAreBoundaryExtrasAndProposedPostingsAreNotExtras() {
    StatementExtra middle = extra(200, "2026-05-15");
    StatementExtra lateMay = extra(201, "2026-05-29");
    StatementExtra earlyMay = extra(202, "2026-05-02");
    StatementExtra proposed = extra(100, "2026-05-10");

    StatementReview result =
        review(
            List.of(line(1, 0, "2026-05-12", "-3.50")),
            List.of(candidate(1, 100, OWN, "-3.50", 2, false)),
            List.of(),
            List.of(middle, lateMay, earlyMay, proposed));

    assertThat(result.extras())
        .extracting(e -> e.extra().postingId(), ExtraReview::boundary)
        .containsExactly(
            tuple(200L, Boundary.NONE), tuple(201L, Boundary.NEXT), tuple(202L, Boundary.PREVIOUS));
    assertThat(result.extra()).isEqualTo(1);
    assertThat(result.boundary()).isEqualTo(2);
  }

  private static StatementExtra extra(long postingId, String date) {
    return new StatementExtra(
        postingId, postingId, LocalDate.parse(date), "ShopAaa", null, new BigDecimal("-1.00"));
  }
}
