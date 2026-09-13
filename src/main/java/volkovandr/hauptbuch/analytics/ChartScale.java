package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * "Nice numbers" tick selection for a chart's value axis (Heckbert's algorithm) — pure geometry
 * maths, no dependency on the book (plan stage b). The range always widens to include zero, since
 * every chart this engine draws has a meaningful zero baseline (a turnover or a balance).
 */
final class ChartScale {

  // The "nice fraction" breakpoints of Heckbert's algorithm — 1/2/5/10 × a power of ten is the
  // fixed vocabulary a human reads a tick as round; ROUND_* picks the nearest of those four,
  // CEIL_* rounds up to guarantee the axis still covers the data.
  private static final double ROUND_UP_TO_2 = 1.5;
  private static final double ROUND_UP_TO_5 = 3;
  private static final double ROUND_UP_TO_10 = 7;
  private static final double CEIL_TO_2 = 1;
  private static final double CEIL_TO_5 = 2;
  private static final double CEIL_TO_10 = 5;

  private ChartScale() {}

  /** A value axis's resolved bounds and the round tick values between them. */
  record Ticks(double min, double max, List<Double> values) {}

  /**
   * Round ticks spanning {@code [dataMin, dataMax]} (widened to include zero), near {@code
   * targetCount} of them.
   */
  static Ticks niceTicks(double dataMin, double dataMax, int targetCount) {
    if (dataMin > dataMax) {
      throw new IllegalArgumentException("dataMin must not exceed dataMax.");
    }
    double min = Math.min(dataMin, 0);
    double max = Math.max(dataMax, 0);
    // min <= max always holds here (both are widened to include zero); a collapsed range means
    // both landed on zero (no data, or every value zero) — pad it so a step can still be chosen.
    if (min >= max) {
      min -= 1;
      max += 1;
    }
    double range = niceNumber(max - min, false);
    double step = niceNumber(range / Math.max(targetCount - 1, 1), true);
    double niceMin = Math.floor(min / step) * step;
    double niceMax = Math.ceil(max / step) * step;

    List<Double> values = new ArrayList<>();
    int steps = (int) Math.round((niceMax - niceMin) / step);
    for (int i = 0; i <= steps; i++) {
      values.add(niceMin + i * step);
    }
    return new Ticks(niceMin, niceMax, values);
  }

  /** The nearest "nice" number (1/2/5 × a power of ten) to {@code range}. */
  private static double niceNumber(double range, boolean round) {
    double exponent = Math.floor(Math.log10(range));
    double fraction = range / Math.pow(10, exponent);
    double niceFraction;
    if (round) {
      if (fraction < ROUND_UP_TO_2) {
        niceFraction = 1;
      } else if (fraction < ROUND_UP_TO_5) {
        niceFraction = 2;
      } else if (fraction < ROUND_UP_TO_10) {
        niceFraction = 5;
      } else {
        niceFraction = 10;
      }
    } else {
      if (fraction <= CEIL_TO_2) {
        niceFraction = 1;
      } else if (fraction <= CEIL_TO_5) {
        niceFraction = 2;
      } else if (fraction <= CEIL_TO_10) {
        niceFraction = 5;
      } else {
        niceFraction = 10;
      }
    }
    return niceFraction * Math.pow(10, exponent);
  }
}
