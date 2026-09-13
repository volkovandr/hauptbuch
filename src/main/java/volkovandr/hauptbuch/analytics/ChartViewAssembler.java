package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link ReportGrid} into a chart renderer's display-ready {@link ChartView} — the chart
 * counterpart to {@link ReportTableViewAssembler}.
 *
 * <p>{@link ReportGrid#rows()} means two different things depending on how the spec populated it
 * (see {@link ReportSpec}'s javadoc): a real {@code rows} dimension renders as <strong>small
 * multiples</strong> — one independent panel per row, each a single-series chart; a {@code series}
 * dimension (or no row axis at all) renders as <strong>one panel</strong> whose rows become the
 * legend entries. A pie chart is always small multiples (one pie per row, or one pie total) — a
 * single pie cannot show more than one series at once.
 */
final class ChartViewAssembler {

  private static final String PIE_REFUSAL =
      "This measure can go negative, so a pie chart cannot show it honestly (§7.4).";

  private ChartViewAssembler() {}

  static ChartView assemble(
      String title,
      ReportSpec spec,
      ReportGrid grid,
      String baseCurrency,
      Renderer renderer,
      boolean trendLine) {
    if (spec.measures().size() != 1) {
      throw new IllegalArgumentException("A chart renderer needs exactly one measure.");
    }
    List<String> columnLabels = grid.columns().stream().map(AxisNode::label).toList();
    List<ChartLine> lines = new ArrayList<>();
    for (int i = 0; i < grid.rows().size(); i++) {
      lines.add(new ChartLine(grid.rows().get(i).label(), grid.cells().get(i)));
    }

    if (renderer == Renderer.PIE && anyNegative(lines)) {
      return new ChartView(
          title,
          ScopeHeaderText.render(spec.scope()),
          grid.resolvedStart(),
          grid.resolvedEnd(),
          List.of(),
          PIE_REFUSAL);
    }

    boolean smallMultiples = renderer == Renderer.PIE || !spec.rows().isEmpty();
    List<ChartPanel> panels =
        smallMultiples
            ? smallMultiplePanels(columnLabels, lines, renderer, trendLine)
            : List.of(new ChartPanel("", onePanelSvg(columnLabels, lines, renderer, trendLine)));

    return new ChartView(
        title,
        ScopeHeaderText.render(spec.scope()),
        grid.resolvedStart(),
        grid.resolvedEnd(),
        panels,
        null);
  }

  private static List<ChartPanel> smallMultiplePanels(
      List<String> columnLabels, List<ChartLine> lines, Renderer renderer, boolean trendLine) {
    List<ChartPanel> panels = new ArrayList<>();
    boolean caption = lines.size() > 1;
    for (ChartLine chartLine : lines) {
      String svg = onePanelSvg(columnLabels, List.of(chartLine), renderer, trendLine);
      panels.add(new ChartPanel(caption ? chartLine.label() : "", svg));
    }
    return panels;
  }

  private static String onePanelSvg(
      List<String> columnLabels, List<ChartLine> lines, Renderer renderer, boolean trendLine) {
    return switch (renderer) {
      case LINE -> ChartSvgWriter.line(columnLabels, lines, trendLine);
      case BAR -> ChartSvgWriter.bar(columnLabels, lines);
      case PIE -> ChartSvgWriter.pie(columnLabels, lines.get(0));
      case TABLE -> throw new IllegalArgumentException("TABLE has no chart panel.");
    };
  }

  private static boolean anyNegative(List<ChartLine> lines) {
    for (ChartLine chartLine : lines) {
      for (Cell cell : chartLine.values()) {
        if (cell instanceof Cell.Value value && value.amount().signum() < 0) {
          return true;
        }
      }
    }
    return false;
  }
}
