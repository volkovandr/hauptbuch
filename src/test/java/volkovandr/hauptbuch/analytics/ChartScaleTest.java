package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ChartScale}'s "nice numbers" tick selection — pure geometry
 * maths, no DB (plan stage b: "geometry maths... is unit-testable and belongs there").
 */
class ChartScaleTest {

  @Test
  void picksRoundTicksCoveringTheDataRange() {
    ChartScale.Ticks ticks = ChartScale.niceTicks(0, 95, 5);

    assertThat(ticks.min()).isLessThanOrEqualTo(0);
    assertThat(ticks.max()).isGreaterThanOrEqualTo(95);
    assertThat(ticks.values()).isNotEmpty();
    // Every tick is evenly spaced by the same round step.
    double step = ticks.values().get(1) - ticks.values().get(0);
    for (int i = 1; i < ticks.values().size(); i++) {
      assertThat(ticks.values().get(i) - ticks.values().get(i - 1)).isCloseTo(step, within(1e-9));
    }
  }

  @Test
  void alwaysIncludesZeroInTheRange() {
    // A chart baseline needs zero on it even when every data point is positive (or negative).
    ChartScale.Ticks ticks = ChartScale.niceTicks(40, 60, 5);

    assertThat(ticks.min()).isLessThanOrEqualTo(0);
    assertThat(ticks.max()).isGreaterThanOrEqualTo(60);
  }

  @Test
  void handlesAllValuesEqualByPaddingTheRange() {
    ChartScale.Ticks ticks = ChartScale.niceTicks(10, 10, 5);

    assertThat(ticks.min()).isLessThan(ticks.max());
    assertThat(ticks.values().size()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void handlesNegativeData() {
    ChartScale.Ticks ticks = ChartScale.niceTicks(-50, -10, 5);

    assertThat(ticks.min()).isLessThanOrEqualTo(-50);
    assertThat(ticks.max()).isGreaterThanOrEqualTo(0);
  }
}
