package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): the pure opening-balance winner proposal (import.md §5.1; plan c3) —
 * the earlier-dated one wins, a same-date tie breaks toward the non-zero one, and the degenerate
 * cases (no Money balance, no Hauptbuch balance).
 */
class OpeningBalanceReconciliationTest {

  private static OpeningBalanceReconciliation.Balance balance(String date, String amount) {
    return new OpeningBalanceReconciliation.Balance(LocalDate.parse(date), new BigDecimal(amount));
  }

  @Test
  void nothingToReconcileWhenMoneyStagedNoOpeningBalance() {
    assertThat(OpeningBalanceReconciliation.propose(balance("2004-01-01", "100"), null)).isNull();
    assertThat(OpeningBalanceReconciliation.propose(null, null)).isNull();
  }

  @Test
  void takesMoneyWhenHauptbuchHasNoneOfItsOwn() {
    assertThat(OpeningBalanceReconciliation.propose(null, balance("2004-01-01", "100")))
        .isEqualTo(OpeningBalanceChoice.TAKE_MONEY);
  }

  @Test
  void theEarlierDatedOpeningBalanceWins() {
    assertThat(
            OpeningBalanceReconciliation.propose(
                balance("2005-06-01", "100"), balance("2004-01-01", "100")))
        .isEqualTo(OpeningBalanceChoice.TAKE_MONEY);
    assertThat(
            OpeningBalanceReconciliation.propose(
                balance("2004-01-01", "100"), balance("2005-06-01", "100")))
        .isEqualTo(OpeningBalanceChoice.KEEP_HAUPTBUCH);
  }

  @Test
  void sameDateTieBreaksTowardTheNonZeroOne() {
    // Hauptbuch's is a placeholder zero, Money's is real → take Money's.
    assertThat(
            OpeningBalanceReconciliation.propose(
                balance("2004-01-01", "0"), balance("2004-01-01", "1234.56")))
        .isEqualTo(OpeningBalanceChoice.TAKE_MONEY);
    // Money's is the zero one → keep Hauptbuch's.
    assertThat(
            OpeningBalanceReconciliation.propose(
                balance("2004-01-01", "500"), balance("2004-01-01", "0")))
        .isEqualTo(OpeningBalanceChoice.KEEP_HAUPTBUCH);
  }

  @Test
  void sameDateAndBothNonZeroKeepsHauptbuchsOwn() {
    assertThat(
            OpeningBalanceReconciliation.propose(
                balance("2004-01-01", "100"), balance("2004-01-01", "200")))
        .isEqualTo(OpeningBalanceChoice.KEEP_HAUPTBUCH);
  }
}
