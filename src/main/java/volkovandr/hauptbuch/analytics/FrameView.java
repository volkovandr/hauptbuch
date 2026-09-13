package volkovandr.hauptbuch.analytics;

/**
 * One Frame of the reporting page's Layout grid, ready to render (reporting.md §11): its position,
 * its picker's field name and current selection, and — when it names a known Preset and the book
 * has a base currency — that Preset's rendered table or chart.
 *
 * @param fieldName the picker's {@code <select name="...">}, encoding this Frame's grid position
 * @param selectedSlug the raw stored/submitted slug, even when it names no known Preset (§16)
 * @param configured whether {@code selectedSlug} resolved to a known Preset
 */
record FrameView(
    int rowPosition,
    int colPosition,
    String fieldName,
    String selectedSlug,
    boolean configured,
    boolean baseCurrencyUnset,
    ReportTableView report,
    ChartView chart) {}
