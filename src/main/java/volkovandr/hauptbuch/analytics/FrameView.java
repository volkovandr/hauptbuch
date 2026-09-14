package volkovandr.hauptbuch.analytics;

/**
 * One Frame of the reporting page's Layout grid, ready to render (reporting.md §11, plan stage d2):
 * its picker's field name and current selection (used only by {@code /reports/layout}'s editor —
 * the read-only {@code /reports} page ignores both), and — when it names a known Preset/Report and
 * the book has a base currency — that Report's name and rendered table or chart plus a link to its
 * own full page (where a Preset's "Copy to my reports" or a saved Report's rename/duplicate/delete
 * live). Grid position is not carried here — the template lays Frames out purely by list order
 * inside a CSS grid. Shared by both screens so neither can drift on how a Frame renders (the shared
 * {@code fragments/frame.html} fragment reads its model variables straight off this record's field
 * names via {@code th:with}).
 *
 * @param fieldName the picker's {@code <select name="...">}, encoding this Frame's grid position
 * @param selectedValue the raw stored/submitted, {@link FrameSelection}-encoded value, even when it
 *     names no known Preset/Report (§16)
 * @param configured whether {@code selectedValue} resolved to a known Preset or Report
 * @param title the resolved Preset/Report's name, the Frame's heading; {@code null} when
 *     unconfigured
 * @param openFullReportUrl the configured selection's own page; {@code null} when unconfigured
 */
record FrameView(
    String fieldName,
    String selectedValue,
    boolean configured,
    boolean baseCurrencyUnset,
    String title,
    ReportTableView report,
    ChartView chart,
    String openFullReportUrl) {}
