package volkovandr.hauptbuch.analytics.repository;

/**
 * One Frame of a Layout (reporting.md §11): its grid position and the Preset it shows. {@code
 * presetSlug} is {@code null} for an emptied Frame — a saved Report's own reference (stage d) will
 * add a sibling nullable column rather than overload this one.
 */
public record LayoutFrameRow(int rowPosition, int colPosition, String presetSlug) {}
