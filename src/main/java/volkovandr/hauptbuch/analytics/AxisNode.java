package volkovandr.hauptbuch.analytics;

/**
 * One label on a grid axis (a row or a column) — the dimension value's stable key (an account id, a
 * currency code, a month bucket key, …) and its display label.
 *
 * @param key stable identifier, used to correlate query rows to grid cells
 * @param label display text
 */
public record AxisNode(String key, String label) {}
