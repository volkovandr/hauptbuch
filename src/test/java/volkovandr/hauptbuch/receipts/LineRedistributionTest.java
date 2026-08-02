package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.receipts.LineRedistribution.Line;

/**
 * Unit tier (plan §1.5): the pure post-process redistribute arithmetic (plan §9f). "Spread this
 * line over the others, remove it" — proportional to each absorbing line's amount, cent-level
 * largest-remainder so the total is preserved exactly. Real-account transfer legs never absorb;
 * beneficiary lines and negative lines do; it is refused when the absorbers sum to zero.
 */
class LineRedistributionTest {

  private static Line cat(String amount) {
    return new Line(new BigDecimal(amount), false);
  }

  private static Line transfer(String amount) {
    return new Line(new BigDecimal(amount), true);
  }

  @Test
  void spreadsProportionallyAndRemovesTheLine() {
    List<BigDecimal> result =
        LineRedistribution.spread(List.of(cat("10.00"), cat("20.00"), cat("3.00")), 2);

    assertThat(result).containsExactly(new BigDecimal("11.00"), new BigDecimal("22.00"));
  }

  @Test
  void usesLargestRemainderSoTheTotalIsPreservedToTheCent() {
    // 0,01 over two equal lines: each ideal 0,005 → both floor to 0,00, the odd cent goes to the
    // first (largest-remainder tie broken by lowest index). The whole 20,01 survives.
    List<BigDecimal> result =
        LineRedistribution.spread(List.of(cat("10.00"), cat("10.00"), cat("0.01")), 2);

    assertThat(result).containsExactly(new BigDecimal("10.01"), new BigDecimal("10.00"));
    assertThat(result.get(0).add(result.get(1))).isEqualByComparingTo("20.01");
  }

  @Test
  void realAccountTransferLegsDoNotAbsorb() {
    List<BigDecimal> result =
        LineRedistribution.spread(List.of(cat("10.00"), transfer("5.00"), cat("3.00")), 2);

    // the 3,00 falls entirely on the one category line; the transfer leg is untouched.
    assertThat(result).containsExactly(new BigDecimal("13.00"), new BigDecimal("5.00"));
  }

  @Test
  void negativeLinesParticipateWithNegativeShares() {
    List<BigDecimal> result =
        LineRedistribution.spread(List.of(cat("30.00"), cat("-10.00"), cat("4.00")), 2);

    // base 20,00: +6,00 to the 30 and −2,00 to the −10; sum stays 24,00.
    assertThat(result).containsExactly(new BigDecimal("36.00"), new BigDecimal("-12.00"));
    assertThat(result.get(0).add(result.get(1))).isEqualByComparingTo("24.00");
  }

  @Test
  void refusedWhenEveryOtherLineIsTransfer() {
    assertThatThrownBy(() -> LineRedistribution.spread(List.of(transfer("5.00"), cat("2.00")), 1))
        .isInstanceOf(LineRedistribution.RedistributeRefusedException.class);
  }

  @Test
  void refusedWhenTheAbsorbingLinesSumToZero() {
    assertThatThrownBy(
            () -> LineRedistribution.spread(List.of(cat("10.00"), cat("-10.00"), cat("3.00")), 2))
        .isInstanceOf(LineRedistribution.RedistributeRefusedException.class);
  }
}
