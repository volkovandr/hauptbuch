package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the funding-leg sigil as a checked assertion (register §3.5, issue
 * transaction-register-ui/06). The one comparison — {@code for} ⇒ the funding leg nets to a debit,
 * {@code by} ⇒ a credit — with a net of exactly zero and a real funding account (blank direction)
 * both unverifiable and allowed.
 */
class FundingSigilCheckTest {

  private static void verify(String direction, String net) {
    FundingSigilCheck.verify(direction, new BigDecimal(net));
  }

  @Test
  void forCommitsWhenTheFundingLegNetsDebit() {
    assertThatCode(() -> verify("FOR", "10")).doesNotThrowAnyException();
  }

  @Test
  void byCommitsWhenTheFundingLegNetsCredit() {
    assertThatCode(() -> verify("BY", "-10")).doesNotThrowAnyException();
  }

  @Test
  void forIsRefusedWhenTheFundingLegNetsCredit() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> verify("FOR", "-10"))
        .withMessageContaining("by");
  }

  @Test
  void byIsRefusedWhenTheFundingLegNetsDebit() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> verify("BY", "10"))
        .withMessageContaining("for");
  }

  @Test
  void netOfExactlyZeroCommitsUnderEitherSigil() {
    assertThatCode(() -> verify("FOR", "0")).doesNotThrowAnyException();
    assertThatCode(() -> verify("BY", "0")).doesNotThrowAnyException();
  }

  @Test
  void realFundingAccountIsNotChecked() {
    assertThatCode(() -> verify(null, "-10")).doesNotThrowAnyException();
    assertThatCode(() -> verify("  ", "10")).doesNotThrowAnyException();
  }

  @Test
  void theExplanationNamesTheSigilToUseInstead() {
    assertThat(catchMessage("FOR", "-1")).contains("Use by");
    assertThat(catchMessage("BY", "1")).contains("Use for");
  }

  private static String catchMessage(String direction, String net) {
    try {
      verify(direction, net);
      throw new AssertionError("expected a refusal");
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }
}
