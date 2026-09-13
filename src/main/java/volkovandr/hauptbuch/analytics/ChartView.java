package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * A chart renderer's display-ready model (reporting.md §10), the chart counterpart to {@link
 * ReportTableView}: everything is already built ({@link ChartViewAssembler}), so the template does
 * no computation of its own.
 *
 * @param panels one panel per small multiple (reporting.md §3); a single entry otherwise; empty
 *     when {@code refusalMessage} is set
 * @param refusalMessage the pie's negative-measure refusal (§7.4), {@code null} otherwise
 */
public record ChartView(
    String title,
    String scopeLine,
    LocalDate resolvedStart,
    LocalDate resolvedEnd,
    List<ChartPanel> panels,
    String refusalMessage) {

  /** Defensively copies {@code panels} to an immutable list. */
  public ChartView {
    panels = List.copyOf(panels);
  }
}
