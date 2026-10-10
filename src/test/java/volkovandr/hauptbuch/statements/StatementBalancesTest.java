package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.statements.ExtraReview.Boundary;

/**
 * The opening and closing balance checks (statements.md §6.2): the bank's figure against the
 * ledger's, less what the statement itself explains — matched postings dated outside the period and
 * boundary extras.
 */
class StatementBalancesTest {

  private static final LocalDate START = LocalDate.of(2026, 5, 1);
  private static final LocalDate END = LocalDate.of(2026, 5, 31);

  private static Statement statement(String opening, String closing) {
    return new Statement(
        9L,
        1L,
        1L,
        Statement.STATE_NEW,
        "may.pdf",
        "x",
        START,
        END,
        opening == null ? null : new BigDecimal(opening),
        closing == null ? null : new BigDecimal(closing),
        null,
        null);
  }

  private static LineReview matched(long id, String date, String amount) {
    StatementLine line =
        new StatementLine(
            id, (int) id, LocalDate.parse(date), null, new BigDecimal(amount), "x", null, null, "raw", null);
    StatementMatch match =
        new StatementMatch(
            id, id, id, LocalDate.parse(date), "ShopAaa", new BigDecimal(amount), "reconciled");
    return new LineReview(line, LineStatus.MATCHED, match, List.of());
  }

  private static ExtraReview extra(String date, String amount, Boundary boundary) {
    return new ExtraReview(
        new StatementExtra(50L, 50L, LocalDate.parse(date), "ShopBbb", null, new BigDecimal(amount)),
        boundary);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  @Test
  void agreesWhenBankAndLedgerAreTheSame() {
    StatementReview review = new StatementReview(List.of(matched(1, "2026-05-10", "-20.00")), List.of());

    StatementBalances balances =
        StatementBalances.of(statement("100.00", "80.00"), review, bd("100.00"), bd("80.00"));

    assertThat(balances.opening().unexplained()).isEqualByComparingTo("0");
    assertThat(balances.closing().unexplained()).isEqualByComparingTo("0");
    assertThat(balances.agree()).isTrue();
  }

  @Test
  void aPostingMatchedHereButDatedAfterThePeriodExplainsTheClosingDifference() {
    // The ledger dates it 2 June, so its 31 May balance lacks it; the bank booked it in May.
    StatementReview review =
        new StatementReview(List.of(matched(1, "2026-06-02", "-15.00")), List.of());

    StatementBalances balances =
        StatementBalances.of(statement("100.00", "85.00"), review, bd("100.00"), bd("100.00"));

    assertThat(balances.closing().explained()).isEqualByComparingTo("-15.00");
    assertThat(balances.closing().unexplained()).isEqualByComparingTo("0");
  }

  @Test
  void aPostingMatchedHereButDatedBeforeThePeriodExplainsTheOpeningDifference() {
    // Bought 30 April, booked by the bank 2 May: the ledger's opening has it, the bank's does not.
    StatementReview review =
        new StatementReview(List.of(matched(1, "2026-04-30", "-15.00")), List.of());

    StatementBalances balances =
        StatementBalances.of(statement("100.00", "100.00"), review, bd("85.00"), bd("85.00"));

    assertThat(balances.opening().explained()).isEqualByComparingTo("15.00");
    assertThat(balances.opening().unexplained()).isEqualByComparingTo("0");
    assertThat(balances.closing().unexplained()).isEqualByComparingTo("15.00");
  }

  @Test
  void aNextStatementBoundaryExtraExplainsTheClosingDifference() {
    StatementReview review =
        new StatementReview(List.of(), List.of(extra("2026-05-31", "-8.00", Boundary.NEXT)));

    StatementBalances balances =
        StatementBalances.of(statement(null, "100.00"), review, BigDecimal.ZERO, bd("92.00"));

    assertThat(balances.opening()).isNull();
    assertThat(balances.closing().explained()).isEqualByComparingTo("8.00");
    assertThat(balances.closing().unexplained()).isEqualByComparingTo("0");
  }

  @Test
  void aPreviousStatementBoundaryExtraExplainsTheOpeningDifference() {
    StatementReview review =
        new StatementReview(List.of(), List.of(extra("2026-05-01", "-8.00", Boundary.PREVIOUS)));

    StatementBalances balances =
        StatementBalances.of(statement("92.00", null), review, bd("100.00"), BigDecimal.ZERO);

    assertThat(balances.opening().explained()).isEqualByComparingTo("-8.00");
    assertThat(balances.opening().unexplained()).isEqualByComparingTo("0");
  }

  @Test
  void anOrdinaryExtraExplainsNothing() {
    StatementReview review =
        new StatementReview(List.of(), List.of(extra("2026-05-15", "-8.00", Boundary.NONE)));

    StatementBalances balances =
        StatementBalances.of(statement(null, "100.00"), review, BigDecimal.ZERO, bd("92.00"));

    assertThat(balances.closing().unexplained()).isEqualByComparingTo("8.00");
    assertThat(balances.agree()).isFalse();
  }

  @Test
  void noBalancesMeansNoChecksAndNothingToDisagree() {
    StatementBalances balances =
        StatementBalances.of(
            statement(null, null), new StatementReview(List.of(), List.of()), BigDecimal.ONE, BigDecimal.TEN);

    assertThat(balances.opening()).isNull();
    assertThat(balances.closing()).isNull();
    assertThat(balances.agree()).isTrue();
  }

  @Test
  void greenNeedsLinesAllMatchedNoExtrasAndAgreeingBalances() {
    StatementBalances agreeing = new StatementBalances(null, null);
    StatementReview full = new StatementReview(List.of(matched(1, "2026-05-10", "-1.00")), List.of());

    assertThat(StatementReviewService.green(full, agreeing)).isTrue();
    assertThat(StatementReviewService.green(new StatementReview(List.of(), List.of()), agreeing))
        .isFalse();
    assertThat(
            StatementReviewService.green(
                new StatementReview(
                    full.lines(), List.of(extra("2026-05-15", "-8.00", Boundary.NONE))),
                agreeing))
        .isFalse();
    assertThat(
            StatementReviewService.green(
                new StatementReview(
                    full.lines(), List.of(extra("2026-05-31", "-8.00", Boundary.NEXT))),
                agreeing))
        .isTrue();
    StatementBalances off =
        new StatementBalances(
            null, new StatementBalanceCheck(bd("1"), bd("0"), bd("0"), bd("1")));
    assertThat(StatementReviewService.green(full, off)).isFalse();
  }
}
