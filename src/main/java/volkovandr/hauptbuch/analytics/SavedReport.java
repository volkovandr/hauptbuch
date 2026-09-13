package volkovandr.hauptbuch.analytics;

/**
 * A persisted Report (reporting.md §14, plan stage d): a name and a spec the owner chose to keep,
 * plus the same renderer/trendLine presentation choices a {@link PresetDef} pairs with its spec —
 * the two differ only in that a Preset is code-defined and non-deletable, while a {@code
 * SavedReport} is a row in the {@code report} table, addressed by {@link #reportId()} rather than a
 * slug.
 */
public record SavedReport(
    long reportId, String name, ReportSpec spec, Renderer renderer, boolean trendLine) {}
