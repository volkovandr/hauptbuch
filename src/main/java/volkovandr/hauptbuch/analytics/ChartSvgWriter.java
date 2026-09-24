package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds one chart panel's inline {@code <svg>} in Java (tech-stack §4.5's QR-code precedent, no
 * charting library, reporting.md §10). Works entirely in plot-space {@code double}s — this is
 * screen geometry, never a stored monetary figure, so it does not fall under CLAUDE.md's
 * money-is-never-{@code double} rule.
 *
 * <p>Every method here draws exactly one panel; {@link ChartViewAssembler} decides how many panels
 * a Report needs (small multiples, reporting.md §3) and calls the matching method once per panel.
 * Each SVG element has exactly one format string, in one helper method, reused from every call site
 * that needs that shape — the duplication PMD would otherwise flag on a wall of {@code
 * StringBuilder.append} calls.
 */
final class ChartSvgWriter {

  private static final int WIDTH = 640;
  private static final int HEIGHT = 320;
  private static final int MARGIN_LEFT = 64;
  private static final int MARGIN_RIGHT = 16;
  private static final int MARGIN_TOP = 16;
  private static final int MARGIN_BOTTOM = 56;
  private static final int LEGEND_HEIGHT = 22;
  private static final int TARGET_TICKS = 5;
  private static final double POINT_RADIUS = 3;
  private static final double BAR_GUTTER = 0.15;

  // The ledger palette's own accent colours first (green-ink, oxblood, focus), then a few more
  // hues distinct enough to tell apart — reused rather than a second palette invented for charts.
  private static final String[] PALETTE = {
    "#2f5d3f", "#9e2b25", "#1f6feb", "#b8860b", "#6e4a9e", "#2b8e9e"
  };

  private ChartSvgWriter() {}

  /** A line chart: one polyline per {@code lines} entry, an optional trend overlay (FR-ANA-09). */
  static String line(List<String> columnLabels, List<ChartLine> lines, boolean trend) {
    Plot plot = Plot.of(lines.size() > 1);
    ChartScale.Ticks ticks = ticksFor(lines);

    StringBuilder svg = openSvg();
    appendValueAxis(svg, plot, ticks);
    appendCategoryAxis(svg, plot, columnLabels);
    for (int li = 0; li < lines.size(); li++) {
      List<Double> values = toDoubles(lines.get(li).values());
      String color = color(li);
      appendPolyline(svg, plot, ticks, values, color);
      appendPoints(svg, plot, ticks, values, color);
      if (trend) {
        appendTrend(svg, plot, ticks, values, color);
      }
    }
    appendLegend(svg, plot, lines);
    return closeSvg(svg);
  }

  /** A grouped bar chart: one bar per (x label × {@code lines} entry), sharing a zero baseline. */
  static String bar(List<String> columnLabels, List<ChartLine> lines) {
    Plot plot = Plot.of(lines.size() > 1);
    ChartScale.Ticks ticks = ticksFor(lines);

    StringBuilder svg = openSvg();
    appendValueAxis(svg, plot, ticks);
    appendCategoryAxis(svg, plot, columnLabels);

    double slotWidth = columnLabels.isEmpty() ? 0 : plot.width() / columnLabels.size();
    double groupWidth = slotWidth * (1 - 2 * BAR_GUTTER);
    double barWidth = groupWidth / Math.max(lines.size(), 1);
    double zeroY = plot.plotY(0, ticks);
    for (int li = 0; li < lines.size(); li++) {
      List<Double> values = toDoubles(lines.get(li).values());
      String color = color(li);
      for (int xi = 0; xi < values.size(); xi++) {
        Double value = values.get(xi);
        if (value == null) {
          continue;
        }
        double slotStart = plot.left() + xi * slotWidth + slotWidth * BAR_GUTTER;
        double barX = slotStart + li * barWidth;
        double barY = plot.plotY(value, ticks);
        double top = Math.min(barY, zeroY);
        double height = Math.abs(barY - zeroY);
        svg.append(rect(barX, top, Math.max(barWidth - 1, 1), height, color));
      }
    }
    appendLegend(svg, plot, lines);
    return closeSvg(svg);
  }

  /**
   * A pie chart over one line's values (reporting.md §10's "columns: the slices"). The caller must
   * have already refused a line carrying a negative value (§7.4) — this method assumes every value
   * is non-negative and simply skips blanks/zero-sum. Each slice keeps its column's own colour, and
   * the legend lists only the columns that have a slice. A lone slice is drawn as a full disc: as
   * an arc it would start and end on the same point, which SVG draws as nothing (issue 13).
   */
  static String pie(List<String> columnLabels, ChartLine line) {
    List<Double> values = toDoubles(line.values());
    List<Integer> sliceColumns = new ArrayList<>();
    double total = 0;
    for (int i = 0; i < values.size(); i++) {
      Double v = values.get(i);
      if (v != null && v > 0) {
        sliceColumns.add(i);
        total += v;
      }
    }
    double cx = WIDTH / 2.0;
    double cy = HEIGHT / 2.0 - LEGEND_HEIGHT;
    double radius = Math.min(WIDTH, HEIGHT) / 2.0 - 24;

    StringBuilder svg = openSvg();
    if (sliceColumns.size() == 1) {
      svg.append(circle(cx, cy, radius, color(sliceColumns.get(0))));
    } else {
      double angle = -Math.PI / 2;
      for (int column : sliceColumns) {
        double sweep = (values.get(column) / total) * 2 * Math.PI;
        svg.append(pieSlice(cx, cy, radius, angle, angle + sweep, color(column)));
        angle += sweep;
      }
    }
    appendPieLegend(
        svg, new Plot(0, HEIGHT - LEGEND_HEIGHT, WIDTH, LEGEND_HEIGHT), columnLabels, sliceColumns);
    return closeSvg(svg);
  }

  private static ChartScale.Ticks ticksFor(List<ChartLine> lines) {
    List<Double> allValues = allValues(lines);
    return ChartScale.niceTicks(min(allValues), max(allValues), TARGET_TICKS);
  }

  // ── shared plot geometry ────────────────────────────────────────────────

  /** The plot area inside the chart's margins, in SVG user units. */
  private record Plot(double left, double top, double width, double height) {

    static Plot of(boolean hasLegend) {
      int top = MARGIN_TOP + (hasLegend ? LEGEND_HEIGHT : 0);
      return new Plot(
          MARGIN_LEFT, top, WIDTH - MARGIN_LEFT - MARGIN_RIGHT, HEIGHT - top - MARGIN_BOTTOM);
    }

    double plotX(int index, int count) {
      if (count <= 1) {
        return left + width / 2;
      }
      return left + (width * index) / (count - 1.0);
    }

    double plotY(double value, ChartScale.Ticks ticks) {
      double span = ticks.max() - ticks.min();
      double fraction = span == 0 ? 0 : (value - ticks.min()) / span;
      return top + height - fraction * height;
    }
  }

  private static StringBuilder openSvg() {
    return new StringBuilder(2048)
        .append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" viewBox=\"0 0 ")
        .append(WIDTH)
        .append(' ')
        .append(HEIGHT)
        .append("\" class=\"chart-svg\">");
  }

  private static String closeSvg(StringBuilder svg) {
    return svg.append("</svg>").toString();
  }

  private static void appendValueAxis(StringBuilder svg, Plot plot, ChartScale.Ticks ticks) {
    for (double tick : ticks.values()) {
      double y = plot.plotY(tick, ticks);
      svg.append(gridLine(plot.left(), plot.left() + plot.width(), y))
          .append(valueAxisLabel(plot.left() - 8, y, formatTick(tick)));
    }
  }

  private static void appendCategoryAxis(StringBuilder svg, Plot plot, List<String> columnLabels) {
    for (int i = 0; i < columnLabels.size(); i++) {
      double x = plot.plotX(i, columnLabels.size());
      double y = plot.top() + plot.height() + 14;
      svg.append(categoryAxisLabel(x, y, columnLabels.get(i)));
    }
  }

  private static void appendPolyline(
      StringBuilder svg, Plot plot, ChartScale.Ticks ticks, List<Double> values, String color) {
    StringBuilder path = new StringBuilder();
    boolean drawing = false;
    for (int i = 0; i < values.size(); i++) {
      Double value = values.get(i);
      if (value == null) {
        drawing = false;
        continue;
      }
      double x = plot.plotX(i, values.size());
      double y = plot.plotY(value, ticks);
      path.append(drawing ? " L " : "M ").append(fmt(x)).append(' ').append(fmt(y));
      drawing = true;
    }
    if (path.isEmpty()) {
      return;
    }
    svg.append(strokedPath(path.toString(), color));
  }

  private static void appendPoints(
      StringBuilder svg, Plot plot, ChartScale.Ticks ticks, List<Double> values, String color) {
    for (int i = 0; i < values.size(); i++) {
      Double value = values.get(i);
      if (value == null) {
        continue;
      }
      double x = plot.plotX(i, values.size());
      double y = plot.plotY(value, ticks);
      svg.append(circle(x, y, POINT_RADIUS, color));
    }
  }

  private static void appendTrend(
      StringBuilder svg, Plot plot, ChartScale.Ticks ticks, List<Double> values, String color) {
    TrendLine.fit(values)
        .ifPresent(
            fit -> {
              int lastIndex = values.size() - 1;
              double x1 = plot.plotX(0, values.size());
              double y1 = plot.plotY(TrendLine.valueAt(fit, 0), ticks);
              double x2 = plot.plotX(lastIndex, values.size());
              double y2 = plot.plotY(TrendLine.valueAt(fit, lastIndex), ticks);
              svg.append(dashedTrendLine(x1, y1, x2, y2, color));
            });
  }

  private static void appendLegend(StringBuilder svg, Plot plot, List<ChartLine> lines) {
    if (lines.size() <= 1) {
      return;
    }
    List<Integer> colorIndexes = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      colorIndexes.add(i);
    }
    appendLegendEntries(svg, plot, colorIndexes, lines.stream().map(ChartLine::label).toList());
  }

  /**
   * {@link #appendLegend}'s pie counterpart: one entry per slice, in its column's own colour — kept
   * even for a single slice, since a lone full disc otherwise names nothing.
   */
  private static void appendPieLegend(
      StringBuilder svg, Plot plot, List<String> columnLabels, List<Integer> sliceColumns) {
    appendLegendEntries(
        svg, plot, sliceColumns, sliceColumns.stream().map(columnLabels::get).toList());
  }

  /** The one legend layout: entry {@code i} shows {@code labels[i]} in {@code colorIndexes[i]}. */
  private static void appendLegendEntries(
      StringBuilder svg, Plot plot, List<Integer> colorIndexes, List<String> labels) {
    double entryWidth = Math.min(plot.width() / Math.max(labels.size(), 1), 140);
    double y = 12;
    for (int i = 0; i < labels.size(); i++) {
      double x = plot.left() + i * entryWidth;
      svg.append(legendEntry(x, y, color(colorIndexes.get(i)), labels.get(i)));
    }
  }

  // ── one format string per SVG element shape ────────────────────────────

  private static String gridLine(double x1, double x2, double y) {
    return String.format(
        Locale.ROOT,
        "<line x1=\"%s\" x2=\"%s\" y1=\"%s\" y2=\"%s\" class=\"chart-grid\"/>",
        fmt(x1),
        fmt(x2),
        fmt(y),
        fmt(y));
  }

  private static String valueAxisLabel(double x, double y, String label) {
    return String.format(
        Locale.ROOT,
        "<text x=\"%s\" y=\"%s\" text-anchor=\"end\" dominant-baseline=\"middle\""
            + " class=\"chart-axis-label\">%s</text>",
        fmt(x),
        fmt(y),
        HtmlUtils.htmlEscape(label));
  }

  private static String categoryAxisLabel(double x, double y, String label) {
    return String.format(
        Locale.ROOT,
        "<text x=\"%s\" y=\"%s\" text-anchor=\"end\" class=\"chart-axis-label\""
            + " transform=\"rotate(-35 %s %s)\">%s</text>",
        fmt(x),
        fmt(y),
        fmt(x),
        fmt(y),
        HtmlUtils.htmlEscape(label));
  }

  private static String strokedPath(String d, String color) {
    return String.format(
        Locale.ROOT, "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\"/>", d, color);
  }

  private static String circle(double cx, double cy, double r, String fill) {
    return String.format(
        Locale.ROOT,
        "<circle cx=\"%s\" cy=\"%s\" r=\"%s\" fill=\"%s\"/>",
        fmt(cx),
        fmt(cy),
        fmt(r),
        fill);
  }

  private static String dashedTrendLine(double x1, double y1, double x2, double y2, String color) {
    return String.format(
        Locale.ROOT,
        "<line x1=\"%s\" y1=\"%s\" x2=\"%s\" y2=\"%s\" stroke=\"%s\" stroke-width=\"1.5\""
            + " stroke-dasharray=\"5 4\" class=\"chart-trend\"/>",
        fmt(x1),
        fmt(y1),
        fmt(x2),
        fmt(y2),
        color);
  }

  private static String rect(double x, double y, double width, double height, String fill) {
    return String.format(
        Locale.ROOT,
        "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" fill=\"%s\"/>",
        fmt(x),
        fmt(y),
        fmt(width),
        fmt(height),
        fill);
  }

  private static String pieSlice(
      double cx, double cy, double r, double startAngle, double endAngle, String color) {
    double x1 = cx + r * Math.cos(startAngle);
    double y1 = cy + r * Math.sin(startAngle);
    double x2 = cx + r * Math.cos(endAngle);
    double y2 = cy + r * Math.sin(endAngle);
    int largeArc = (endAngle - startAngle) > Math.PI ? 1 : 0;
    return String.format(
        Locale.ROOT,
        "<path d=\"M %s %s L %s %s A %s %s 0 %s 1 %s %s Z\" fill=\"%s\"/>",
        fmt(cx),
        fmt(cy),
        fmt(x1),
        fmt(y1),
        fmt(r),
        fmt(r),
        largeArc,
        fmt(x2),
        fmt(y2),
        color);
  }

  private static String legendEntry(double x, double y, String color, String label) {
    return String.format(
        Locale.ROOT,
        "<rect x=\"%s\" y=\"%s\" width=\"10\" height=\"10\" fill=\"%s\"/>"
            + "<text x=\"%s\" y=\"%s\" class=\"chart-legend-label\">%s</text>",
        fmt(x),
        fmt(y - 8),
        color,
        fmt(x + 14),
        fmt(y),
        HtmlUtils.htmlEscape(label));
  }

  // ── value helpers ───────────────────────────────────────────────────────

  private static List<Double> toDoubles(List<Cell> cells) {
    List<Double> values = new ArrayList<>();
    for (Cell cell : cells) {
      values.add(cellToDouble(cell));
    }
    return values;
  }

  private static Double cellToDouble(Cell cell) {
    if (cell instanceof Cell.Value value) {
      return value.amount().doubleValue();
    }
    if (cell instanceof Cell.Count count) {
      return (double) count.count();
    }
    return null;
  }

  private static List<Double> allValues(List<ChartLine> lines) {
    List<Double> values = new ArrayList<>();
    for (ChartLine chartLine : lines) {
      for (Double v : toDoubles(chartLine.values())) {
        if (v != null) {
          values.add(v);
        }
      }
    }
    return values;
  }

  private static double min(List<Double> values) {
    return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
  }

  private static double max(List<Double> values) {
    return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
  }

  private static String color(int index) {
    return PALETTE[index % PALETTE.length];
  }

  private static String fmt(double v) {
    return String.format(Locale.ROOT, "%.2f", v);
  }

  private static String formatTick(double v) {
    BigDecimal rounded = BigDecimal.valueOf(v).stripTrailingZeros();
    return rounded.scale() <= 0 ? rounded.toBigInteger().toString() : rounded.toPlainString();
  }
}
