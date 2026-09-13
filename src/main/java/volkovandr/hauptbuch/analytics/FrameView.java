package volkovandr.hauptbuch.analytics;

/**
 * One Frame of the reporting page's Layout grid, ready to render (reporting.md §11): its picker's
 * field name and current selection, and — when it names a known Preset/Report and the book has a
 * base currency — that Report's rendered table or chart plus a link to its own full page (where a
 * Preset's "Copy to my reports" or a saved Report's rename/duplicate/delete live). Grid position is
 * not carried here — the template lays Frames out purely by list order inside a CSS grid.
 *
 * @param fieldName the picker's {@code <select name="...">}, encoding this Frame's grid position
 * @param selectedValue the raw stored/submitted, {@link FrameSelection}-encoded value, even when it
 *     names no known Preset/Report (§16)
 * @param configured whether {@code selectedValue} resolved to a known Preset or Report
 * @param openFullReportUrl the configured selection's own page; {@code null} when unconfigured
 */
record FrameView(
    String fieldName,
    String selectedValue,
    boolean configured,
    boolean baseCurrencyUnset,
    ReportTableView report,
    ChartView chart,
    String openFullReportUrl) {}
