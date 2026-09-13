package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A least-squares linear fit over a line chart's points, indexed by their position (FR-ANA-09).
 * Blank points (no data for that bucket) are skipped rather than treated as zero. Pure geometry
 * maths for placing an overlay line on the chart — never a monetary figure a user reads as text, so
 * {@code double} is the right type here, unlike the ledger's own amounts (CLAUDE.md §1.4).
 */
final class TrendLine {

  /** A line needs at least two points; fewer than that has no unique fit. */
  private static final int MIN_POINTS = 2;

  private TrendLine() {}

  /**
   * {@code fit[0]} is the slope, {@code fit[1]} the intercept; empty with fewer than two points.
   */
  static Optional<double[]> fit(List<Double> values) {
    List<Double> xs = new ArrayList<>();
    List<Double> ys = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      Double value = values.get(i);
      if (value != null) {
        xs.add((double) i);
        ys.add(value);
      }
    }
    int n = xs.size();
    if (n < MIN_POINTS) {
      return Optional.empty();
    }
    double sumX = 0;
    double sumY = 0;
    double sumXy = 0;
    double sumXx = 0;
    for (int i = 0; i < n; i++) {
      sumX += xs.get(i);
      sumY += ys.get(i);
      sumXy += xs.get(i) * ys.get(i);
      sumXx += xs.get(i) * xs.get(i);
    }
    double denominator = n * sumXx - sumX * sumX;
    if (denominator == 0) {
      // Every point shares the same x (possible only if the caller passed duplicate indices) —
      // no single line fits; treat as unfittable rather than dividing by zero.
      return Optional.empty();
    }
    double slope = (n * sumXy - sumX * sumY) / denominator;
    double intercept = (sumY - slope * sumX) / n;
    return Optional.of(new double[] {slope, intercept});
  }

  /** The fitted line's value at index {@code i}. */
  static double valueAt(double[] fit, int i) {
    return fit[0] * i + fit[1];
  }
}
