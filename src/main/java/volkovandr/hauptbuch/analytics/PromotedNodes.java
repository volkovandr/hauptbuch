package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * The ticked nodes a hierarchy filter promotes to its axis's top level (reporting issue 08,
 * reporting.md §6.3): when a hierarchical dimension on an axis (Category, Account, Tag) also has a
 * filter on its own field, the ticked nodes are that axis's top level and their ancestors do not
 * render. Under "amounts booked to …" (posting level) they are the whole top level; under
 * "transactions touching …" (transaction level) the nodes those transactions touch also appear,
 * under their normal roots. A pure function of the spec.
 */
final class PromotedNodes {

  private PromotedNodes() {}

  /**
   * The filter on {@code dimension}'s own field, when {@code dimension} is hierarchical and the
   * spec has one — the filter that promotes its ticked nodes.
   */
  static Optional<ReportFilter> ownFilter(Dimension dimension, ReportSpec spec) {
    if (!AutoExpansion.isNestable(dimension)) {
      return Optional.empty();
    }
    FilterField field = AutoExpansion.toFilterField(dimension);
    return spec.filters().stream()
        .filter(f -> f.field() == field && f.operator() == FilterOperator.IS_ONE_OF)
        .findFirst();
  }

  /** {@code dimension}'s promoted node ids, empty when nothing is promoted. */
  static List<Long> ids(Dimension dimension, ReportSpec spec) {
    return ownFilter(dimension, spec)
        .map(f -> f.values().stream().map(Long::parseLong).toList())
        .orElse(List.of());
  }

  /**
   * The promoted Category/Account node ids across {@code dimensions} (an axis's outer and nested
   * dimension, either may be {@code null}) — both walk the one account tree.
   */
  static List<Long> accountIds(ReportSpec spec, Dimension... dimensions) {
    List<Long> ids = new ArrayList<>();
    for (Dimension dimension : dimensions) {
      if (dimension == Dimension.CATEGORY || dimension == Dimension.ACCOUNT) {
        ids.addAll(ids(dimension, spec));
      }
    }
    return ids;
  }

  /** {@link #accountIds}' own mirror for the tag tree. */
  static List<Long> tagIds(ReportSpec spec, Dimension... dimensions) {
    List<Long> ids = new ArrayList<>();
    for (Dimension dimension : dimensions) {
      if (dimension == Dimension.TAG) {
        ids.addAll(ids(dimension, spec));
      }
    }
    return ids;
  }

  /**
   * {@code filters} plus the nodes {@code spec} promotes on an axis carrying {@code outerDim} and
   * {@code innerDim} (either may be {@code null}), so the account and tag tree queries group by
   * them.
   */
  static QueryConstraints constraints(
      List<ReportFilter> filters, ReportSpec spec, Dimension outerDim, Dimension innerDim) {
    return new QueryConstraints(
        filters, accountIds(spec, outerDim, innerDim), tagIds(spec, outerDim, innerDim));
  }

  /**
   * {@code candidates} without the real roots a "touching" filter on {@code dimension}'s own field
   * listed beside its ticked nodes but no qualifying transaction touched (reporting issue 08): a
   * ticked node always stays, empty or not; a touched root stays because it carries data. Without
   * such a filter nothing is dropped — an empty row is suppression's call (§7.3).
   *
   * @param touched whether the fetched data carries a row for a candidate key
   */
  static Map<String, TopLevelNode> withoutUntouchedRoots(
      Dimension dimension,
      ReportSpec spec,
      Map<String, TopLevelNode> candidates,
      Predicate<String> touched) {
    Optional<ReportFilter> ownFilter = ownFilter(dimension, spec);
    if (ownFilter.isEmpty()) {
      return candidates;
    }
    Set<String> ticked = Set.copyOf(ownFilter.get().values());
    Map<String, TopLevelNode> kept = new LinkedHashMap<>();
    candidates.forEach(
        (key, node) -> {
          if (ticked.contains(key) || touched.test(key)) {
            kept.put(key, node);
          }
        });
    return kept;
  }
}
