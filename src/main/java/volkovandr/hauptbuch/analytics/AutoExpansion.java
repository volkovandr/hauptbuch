package volkovandr.hauptbuch.analytics;

import java.util.Set;

/**
 * The {@code auto} row-expansion rule (reporting.md §9.2): a hierarchical dimension's top-level
 * nodes start expanded when the Report's own filter on that dimension selects exactly one hierarchy
 * node — "you asked about one thing; show its parts" — and collapsed otherwise (two or more nodes,
 * or none). A pure function of the spec alone; which nodes actually exist is irrelevant; only the
 * count of ids the filter names on that field.
 *
 * <p>Only {@link Dimension#CATEGORY}, {@link Dimension#ACCOUNT} and {@link Dimension#TAG} nest a
 * second dimension beneath them (§9.1 ties the expandable tree to a hierarchical dimension); the
 * flat dimensions are never expandable and so never auto-expand.
 */
final class AutoExpansion {

  private static final Set<Dimension> HIERARCHICAL =
      Set.of(Dimension.CATEGORY, Dimension.ACCOUNT, Dimension.TAG);

  private AutoExpansion() {}

  /** Whether {@code dimension} can nest a second dimension beneath it at all (§9.1). */
  static boolean isNestable(Dimension dimension) {
    return dimension != null && HIERARCHICAL.contains(dimension);
  }

  /**
   * Whether {@code nested} may sit beneath {@code outer} on one axis (§3): the outer one must be a
   * hierarchy ({@link #isNestable}), and the nested one neither Date (the ladder fills an axis on
   * its own, §8.2) nor the outer dimension repeated.
   */
  static boolean canNestUnder(Dimension outer, Dimension nested) {
    return isNestable(outer) && nested != null && nested != Dimension.DATE && nested != outer;
  }

  /**
   * {@code auto}'s one-node rule (§9.2), naming the node it expands: the single hierarchy node the
   * spec's own filter on {@code dimension} selects, or an empty set when the filter selects none or
   * two-or-more (or there is no such filter). "You asked about one thing; show its parts" means
   * <em>that one thing's</em> own row — not, as an earlier implementation wrongly took it, every
   * other top-level candidate of the same dimension too.
   */
  static Set<String> autoExpandedKeys(Dimension dimension, ReportSpec spec) {
    return PromotedNodes.ownFilter(dimension, spec)
        .filter(filter -> filter.values().size() == 1)
        .map(filter -> Set.copyOf(filter.values()))
        .orElse(Set.of());
  }

  /**
   * The filter field that names a node of hierarchical {@code dimension} — also the field {@link
   * ReportDataFetcher} scopes a nested breakdown to one outer node with (§3).
   */
  static FilterField toFilterField(Dimension dimension) {
    return switch (dimension) {
      case CATEGORY -> FilterField.CATEGORY;
      case ACCOUNT -> FilterField.ACCOUNT;
      case TAG -> FilterField.TAG;
      default -> throw new IllegalArgumentException("No filter field maps to " + dimension);
    };
  }
}
