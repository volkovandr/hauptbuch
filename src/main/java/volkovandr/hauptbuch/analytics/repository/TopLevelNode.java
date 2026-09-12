package volkovandr.hauptbuch.analytics.repository;

/**
 * One candidate row of a hierarchical dimension, independent of whether it has any data this period
 * — needed so an all-blank row can still be listed, then suppressed by {@link
 * volkovandr.hauptbuch.analytics.ReportSpec#suppressEmptyRows()} (reporting.md §7.3) rather than
 * simply never appearing.
 *
 * @param key stable identifier
 * @param label display label
 * @param type the backing account's {@code type}, for the credit-natural display flip; {@code null}
 *     for a tag
 */
public record TopLevelNode(String key, String label, String type) {}
