package volkovandr.hauptbuch.analytics;

/**
 * One rendered chart (reporting.md §10) — a small multiple when {@link ChartView#panels()} has more
 * than one, a single chart otherwise. {@code svg} is already-built markup ({@link ChartSvgWriter}),
 * so the template inserts it verbatim.
 *
 * @param label the small-multiple caption; blank when there is only one panel
 * @param svg the panel's inline {@code <svg>}
 */
public record ChartPanel(String label, String svg) {}
