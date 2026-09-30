package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;

/**
 * One section of the Recurring cost table — expenses or income — as a category tree in base
 * (data-model §14.4): each node's per-year amount is the subtotal of everything under it, and
 * children are kept by name, so each level lists alphabetically. The root is the section's total.
 */
final class CostTree {

  private final String name;
  private final Map<String, CostTree> children = new TreeMap<>();
  private BigDecimal perYear = BigDecimal.ZERO;

  CostTree(String name) {
    this.name = name;
  }

  /** Adds {@code amount} (per year, in base) here and at every node down {@code path}. */
  void add(List<String> path, BigDecimal amount) {
    perYear = perYear.add(amount);
    if (!path.isEmpty()) {
      children
          .computeIfAbsent(path.get(0), CostTree::new)
          .add(path.subList(1, path.size()), amount);
    }
  }

  /** The section's total per year, in base. */
  BigDecimal sumPerYear() {
    return perYear;
  }

  /** The section's total line. */
  Line total(String base) {
    return Valuation.inBase(base).line(name, 0, null, perYear);
  }

  /** The lines under the total, depth first. */
  List<Line> breakdown(String base) {
    List<Line> lines = new ArrayList<>();
    children.values().forEach(child -> child.flatten(1, Valuation.inBase(base), lines));
    return lines;
  }

  private void flatten(int depth, Valuation valuation, List<Line> lines) {
    lines.add(valuation.line(name, depth, null, perYear));
    children.values().forEach(child -> child.flatten(depth + 1, valuation, lines));
  }
}
