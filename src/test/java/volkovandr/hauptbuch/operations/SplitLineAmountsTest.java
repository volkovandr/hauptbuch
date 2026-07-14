package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the split panel's amount-sign math — the pure functions that turn a split
 * line's typed amount into a signed contribution and back. The load-bearing rule is the mixed-split
 * sign convention (register §3.8, ratified 2026-07-09): {@code +amount} for income, {@code −amount}
 * for expense, with a leading {@code −} storno flowing through; a transfer line (plan stage 7d.3)
 * is signed by its direction instead ({@code TO} outflow, {@code FROM} inflow).
 */
class SplitLineAmountsTest {

  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";

  // ── signed contribution (register §3.8, mixed-split rule) ──────────────────────

  @Test
  void expenseLineContributesNegatively() {
    assertThat(SplitLineAmounts.signedContribution("20", EXPENSE)).isEqualByComparingTo("-20");
  }

  @Test
  void incomeLineContributesPositively() {
    assertThat(SplitLineAmounts.signedContribution("3", INCOME)).isEqualByComparingTo("3");
  }

  @Test
  void stornoOnExpenseLineCountsPositively() {
    // A negative amount on an expense line reverses the spend — it counts as an inflow (§3.8).
    assertThat(SplitLineAmounts.signedContribution("-5", EXPENSE)).isEqualByComparingTo("5");
  }

  @Test
  void stornoOnIncomeLineCountsNegatively() {
    assertThat(SplitLineAmounts.signedContribution("−3", INCOME)).isEqualByComparingTo("-3");
  }

  @Test
  void rejectsBlankLineAmount() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> SplitLineAmounts.signedContribution("  ", EXPENSE));
  }

  // ── transfer contribution (register §3.8, plan stage 7d.3) ─────────────────────

  @Test
  void outboundTransferLineContributesNegativelyLikeAnExpense() {
    assertThat(SplitLineAmounts.transferContribution("20", "TO")).isEqualByComparingTo("-20");
  }

  @Test
  void fromTransferLineContributesPositivelyLikeIncome() {
    assertThat(SplitLineAmounts.transferContribution("20", "FROM")).isEqualByComparingTo("20");
  }

  @Test
  void stornoOnaToTransferLineCountsPositively() {
    assertThat(SplitLineAmounts.transferContribution("-5", "TO")).isEqualByComparingTo("5");
  }

  // ── lenient contribution (the panel readout's incomplete-line tolerance) ───────

  @Test
  void lenientContributionIsZeroForanIncompleteLine() {
    assertThat(SplitLineAmounts.lenientContribution("", EXPENSE, "")).isEqualByComparingTo("0");
    assertThat(SplitLineAmounts.lenientContribution("20", "", "")).isEqualByComparingTo("0");
    assertThat(SplitLineAmounts.lenientContribution("nonsense", EXPENSE, ""))
        .isEqualByComparingTo("0");
  }

  @Test
  void lenientContributionSignsaCompleteTransferLineByDirection() {
    assertThat(SplitLineAmounts.lenientContribution("50", "", "TO")).isEqualByComparingTo("-50");
    assertThat(SplitLineAmounts.lenientContribution("50", "", "FROM")).isEqualByComparingTo("50");
  }

  // ── amount text (reconstruction for the panel's edit mode) ─────────────────────

  @Test
  void amountTextInvertsanExpenseLegToaBareMagnitude() {
    assertThat(SplitLineAmounts.amountText(new BigDecimal("20"), EXPENSE)).isEqualTo("20,00");
  }

  @Test
  void amountTextInvertsanIncomeLegAndKeepsaStornoSign() {
    assertThat(SplitLineAmounts.amountText(new BigDecimal("-3"), INCOME)).isEqualTo("3,00");
    assertThat(SplitLineAmounts.amountText(new BigDecimal("3"), INCOME)).isEqualTo("-3,00");
  }
}
