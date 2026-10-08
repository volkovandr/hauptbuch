package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tier: the register dock's reconciled-leg notice names the statement by its month. */
class StatementOfLegTest {

  @Test
  void noticeNamesTheAccountAndTheStatementsMonth() {
    StatementOfLeg leg = new StatementOfLeg("BankAaa-EUR", LocalDate.of(2026, 5, 1), "may.csv");

    assertThat(leg.notice()).isEqualTo("BankAaa-EUR leg reconciled — statement 2026-05");
  }

  @Test
  void noticeFallsBackToTheFileNameWithoutPeriod() {
    StatementOfLeg leg = new StatementOfLeg("BankAaa-EUR", null, "may.csv");

    assertThat(leg.notice()).isEqualTo("BankAaa-EUR leg reconciled — statement may.csv");
  }
}
