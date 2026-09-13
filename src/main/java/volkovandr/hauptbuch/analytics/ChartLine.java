package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * One plotted line, bar-group or pie's worth of values (reporting.md §10) — a legend entry when
 * more than one accompanies a chart. {@code values} carries one {@link Cell} per x-axis point,
 * reusing the engine's own blank/illegal/value distinction (§7.2) rather than inventing a parallel
 * one: a blank point leaves a gap, an illegal one is skipped with the chart still drawn from the
 * rest.
 *
 * @param label the legend/small-multiple caption
 * @param values one cell per x-axis label, same order and length
 */
record ChartLine(String label, List<Cell> values) {

  /** Defensively copies {@code values} to an immutable list. */
  ChartLine {
    values = List.copyOf(values);
  }
}
