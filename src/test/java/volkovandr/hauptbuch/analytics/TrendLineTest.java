package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link TrendLine}'s least-squares fit — pure geometry maths (FR-ANA-09,
 * plan stage b), no dependency on the book.
 */
class TrendLineTest {

  @Test
  void fitsPerfectLineExactly() {
    Optional<double[]> fit = TrendLine.fit(Arrays.asList(1.0, 2.0, 3.0, 4.0));

    assertThat(fit).isPresent();
    assertThat(TrendLine.valueAt(fit.get(), 0)).isCloseTo(1.0, within(1e-9));
    assertThat(TrendLine.valueAt(fit.get(), 3)).isCloseTo(4.0, within(1e-9));
  }

  @Test
  void ignoresBlankPointsWhenFitting() {
    // Blanks (no data that month) must not drag the fit toward zero.
    Optional<double[]> fit = TrendLine.fit(Arrays.asList(1.0, null, 3.0, null, 5.0));

    assertThat(fit).isPresent();
    assertThat(TrendLine.valueAt(fit.get(), 4)).isCloseTo(5.0, within(1e-9));
  }

  @Test
  void isEmptyWithFewerThanTwoPoints() {
    assertThat(TrendLine.fit(Arrays.asList((Double) null, 3.0, null))).isEmpty();
    assertThat(TrendLine.fit(Arrays.asList())).isEmpty();
  }

  @Test
  void fitsFlatLine() {
    Optional<double[]> fit = TrendLine.fit(Arrays.asList(5.0, 5.0, 5.0));

    assertThat(fit).isPresent();
    assertThat(TrendLine.valueAt(fit.get(), 0)).isCloseTo(5.0, within(1e-9));
    assertThat(TrendLine.valueAt(fit.get(), 2)).isCloseTo(5.0, within(1e-9));
  }
}
