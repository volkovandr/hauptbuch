package volkovandr.hauptbuch.analytics.repository;

/**
 * One Frame of a Layout (reporting.md §11): its grid position and what it shows — a Preset by
 * {@code presetSlug}, a saved Report by {@code reportId}, or neither (an emptied Frame). At most
 * one of the two is ever non-null ({@code layout_frame_one_reference_check}, plan stage d).
 */
public record LayoutFrameRow(int rowPosition, int colPosition, String presetSlug, Long reportId) {}
