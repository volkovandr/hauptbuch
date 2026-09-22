package volkovandr.hauptbuch.analytics;

import java.util.Set;

/**
 * A persisted Report (reporting.md §14, plan stage d): a name and a spec the owner chose to keep,
 * plus the same renderer/trendLine presentation choices a {@link PresetDef} pairs with its spec —
 * the two differ only in that a Preset is code-defined and non-deletable, while a {@code
 * SavedReport} is a row in the {@code report} table, addressed by {@link #reportId()} rather than a
 * slug.
 *
 * @param expandedNodeKeys the remembered row-tree expansion state (reporting.md §9.1, plan stage
 *     e2): {@code null} when the owner has never hand-toggled a node, in which case every render
 *     falls back to {@code auto} (§9.2); otherwise the literal set of expanded top-level keys,
 *     which takes over from {@code auto} for good.
 */
public record SavedReport(
    long reportId,
    String name,
    ReportSpec spec,
    Renderer renderer,
    boolean trendLine,
    Set<String> expandedNodeKeys) {

  /** Defensively copies {@link #expandedNodeKeys} when present. */
  public SavedReport {
    expandedNodeKeys = expandedNodeKeys == null ? null : Set.copyOf(expandedNodeKeys);
  }

  /** A newly saved Report always starts with no explicit expansion state — {@code auto} decides. */
  public SavedReport(
      long reportId, String name, ReportSpec spec, Renderer renderer, boolean trendLine) {
    this(reportId, name, spec, renderer, trendLine, null);
  }
}
