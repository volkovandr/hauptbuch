package volkovandr.hauptbuch.analytics;

/**
 * A Preset's URL slug, spec, and the presentation choices the controller — not the spec — carries
 * (reporting.md §14).
 */
record PresetDef(
    String slug, ReportSpec spec, String title, Renderer renderer, boolean trendLine) {}
