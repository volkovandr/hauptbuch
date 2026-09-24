package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Unit tier (CLAUDE.md §6): {@link ChartSvgWriter} emits well-formed {@code <svg>} for a crafted
 * grid (plan stage b) — no DB dependency, so this belongs with the other pure-geometry tests
 * ({@link ChartScaleTest}, {@link TrendLineTest}), not the integration tier.
 */
class ChartSvgWriterTest {

  private static Cell value(String amount) {
    return new Cell.Value(new BigDecimal(amount), "EUR");
  }

  private static Document parse(String svg)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void lineChartIsWellFormedSvgWithOnePointPerLabel()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Jan 2026", "Feb 2026", "Mar 2026");
    ChartLine series = new ChartLine("Net worth", List.of(value("100"), Cell.BLANK, value("300")));

    String svg = ChartSvgWriter.line(columnLabels, List.of(series), false);

    Document doc = parse(svg);
    assertThat(doc.getDocumentElement().getTagName()).isEqualTo("svg");
    NodeList circles = doc.getElementsByTagName("circle");
    // One point per non-blank value — the blank Feb leaves a gap, not a plotted zero.
    assertThat(circles.getLength()).isEqualTo(2);
    assertThat(doc.getElementsByTagName("path").getLength()).isEqualTo(1);
  }

  @Test
  void lineChartWithTrendAddsDashedOverlay()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Jan 2026", "Feb 2026", "Mar 2026");
    ChartLine series =
        new ChartLine("Net worth", List.of(value("100"), value("200"), value("300")));

    String svg = ChartSvgWriter.line(columnLabels, List.of(series), true);

    Document doc = parse(svg);
    assertThat(svg).contains("chart-trend");
    assertThat(doc.getElementsByTagName("path").getLength()).isEqualTo(1);
  }

  @Test
  void multipleLinesRenderLegendEntryEach()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Food", "Fuel");
    ChartLine thisMonth = new ChartLine("Sep 2026", List.of(value("50"), value("20")));
    ChartLine lastMonth = new ChartLine("Aug 2026", List.of(value("40"), value("30")));

    String svg = ChartSvgWriter.bar(columnLabels, List.of(lastMonth, thisMonth));

    Document doc = parse(svg);
    assertThat(svg).contains("Sep 2026").contains("Aug 2026");
    // Two series × two categories = four bars.
    assertThat(doc.getElementsByTagName("rect").getLength()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void barChartHandlesNegativeValueBelowTheBaseline()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Food", "Refunds");
    ChartLine line = new ChartLine("Net", List.of(value("50"), value("-20")));

    String svg = ChartSvgWriter.bar(columnLabels, List.of(line));

    assertThatCode(() -> parse(svg)).doesNotThrowAnyException();
    assertThat(parse(svg).getElementsByTagName("rect").getLength()).isEqualTo(2);
  }

  @Test
  void pieChartRendersOneSlicePerPositiveValue()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Food", "Fuel", "Rent");
    ChartLine line = new ChartLine("Total", List.of(value("100"), value("50"), Cell.BLANK));

    String svg = ChartSvgWriter.pie(columnLabels, line);

    Document doc = parse(svg);
    // Two positive slices (Food, Fuel); Rent is blank and contributes no wedge.
    assertThat(doc.getElementsByTagName("path").getLength()).isEqualTo(2);
    assertThat(svg).contains("Food").contains("Fuel");
  }

  @Test
  void pieLegendOmitsColumnsWithoutSlice() {
    // Issue 13: a zero or blank column draws no wedge, so a legend entry for it names nothing.
    List<String> columnLabels = List.of("Food", "Fuel", "Rent");
    ChartLine line = new ChartLine("Total", List.of(value("100"), value("0"), Cell.BLANK));

    String svg = ChartSvgWriter.pie(columnLabels, line);

    assertThat(svg).contains("Food").doesNotContain("Fuel").doesNotContain("Rent");
  }

  @Test
  void pieChartWithSingleNonZeroValueDrawsFullDisc()
      throws ParserConfigurationException, SAXException, IOException {
    // Issue 13: a 100% slice as an arc starts and ends on the same point, and SVG draws nothing
    // for such an arc (SVG 1.1 §F.6.2) — it must be a full circle instead.
    List<String> columnLabels = List.of("Cash", "Bank", "Card");
    ChartLine line = new ChartLine("Total", List.of(Cell.BLANK, value("250"), value("0")));

    String svg = ChartSvgWriter.pie(columnLabels, line);

    Document doc = parse(svg);
    assertThat(doc.getElementsByTagName("path").getLength()).isZero();
    NodeList circles = doc.getElementsByTagName("circle");
    assertThat(circles.getLength()).isEqualTo(1);
    // The disc keeps its column's own colour, the same one its legend entry shows.
    String discFill = circles.item(0).getAttributes().getNamedItem("fill").getNodeValue();
    NodeList legendSwatches = doc.getElementsByTagName("rect");
    assertThat(legendSwatches.getLength()).isEqualTo(1);
    assertThat(legendSwatches.item(0).getAttributes().getNamedItem("fill").getNodeValue())
        .isEqualTo(discFill);
    assertThat(svg).contains("Bank").doesNotContain("Cash").doesNotContain("Card");
  }

  @Test
  void pieSlicesKeepTheirColumnsColourWhenEarlierColumnIsEmpty()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Food", "Fuel", "Rent");
    ChartLine withFood = new ChartLine("A", List.of(value("10"), value("20"), value("30")));
    ChartLine withoutFood = new ChartLine("B", List.of(Cell.BLANK, value("20"), value("30")));

    Document all = parse(ChartSvgWriter.pie(columnLabels, withFood));
    Document noFood = parse(ChartSvgWriter.pie(columnLabels, withoutFood));

    // Fuel is the second wedge in the first pie and the first in the second — same colour both.
    String fuelInAll =
        all.getElementsByTagName("path")
            .item(1)
            .getAttributes()
            .getNamedItem("fill")
            .getNodeValue();
    String fuelInNoFood =
        noFood
            .getElementsByTagName("path")
            .item(0)
            .getAttributes()
            .getNamedItem("fill")
            .getNodeValue();
    assertThat(fuelInNoFood).isEqualTo(fuelInAll);
  }

  @Test
  void pieChartWithNoPositiveValuesDrawsNoSlices()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("Food");
    ChartLine line = new ChartLine("Total", List.of(Cell.BLANK));

    String svg = ChartSvgWriter.pie(columnLabels, line);

    assertThatCode(() -> parse(svg)).doesNotThrowAnyException();
    assertThat(parse(svg).getElementsByTagName("path").getLength()).isEqualTo(0);
  }

  @Test
  void escapesLabelsInAxisTextAndLegend()
      throws ParserConfigurationException, SAXException, IOException {
    List<String> columnLabels = List.of("A & B");
    ChartLine a = new ChartLine("<script>", List.of(value("1")));
    ChartLine b = new ChartLine("Other", List.of(value("2")));

    String svg = ChartSvgWriter.line(columnLabels, List.of(a, b), false);

    assertThatCode(() -> parse(svg)).doesNotThrowAnyException();
    assertThat(svg).doesNotContain("<script>");
  }
}
