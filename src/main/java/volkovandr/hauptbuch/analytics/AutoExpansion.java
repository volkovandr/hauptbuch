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
   * A cross-dimension nesting node's synthetic subtree-filter key (§3) is never a real hierarchy id
   * — it is the per-currency "personal debts" pseudo-bucket ({@code ACCOUNT_DIMENSION_KEY} in
   * {@code ReportQueryRepository}), an aggregate across every person's debt leaf in one currency,
   * not a single account subtree. It is therefore never expandable.
   */
  static boolean isPersonLeafBucket(String key) {
    return key != null && key.startsWith("personal:");
  }

  /** {@code auto}'s one-node rule (§9.2), for the given dimension against the whole spec. */
  static boolean startsExpanded(Dimension dimension, ReportSpec spec) {
    if (!isNestable(dimension)) {
      return false;
    }
    FilterField field = toFilterField(dimension);
    for (ReportFilter filter : spec.filters()) {
      if (filter.field() == field
          && filter.operator() == FilterOperator.IS_ONE_OF
          && filter.values().size() == 1) {
        return true;
      }
    }
    return false;
  }

  private static FilterField toFilterField(Dimension dimension) {
    return switch (dimension) {
      case CATEGORY -> FilterField.CATEGORY;
      case ACCOUNT -> FilterField.ACCOUNT;
      case TAG -> FilterField.TAG;
      default -> throw new IllegalArgumentException("No filter field maps to " + dimension);
    };
  }
}
