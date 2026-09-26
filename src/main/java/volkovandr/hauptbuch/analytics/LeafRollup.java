package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import volkovandr.hauptbuch.analytics.repository.LeafBalanceFact;
import volkovandr.hauptbuch.analytics.repository.LeafTurnoverFact;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;

/**
 * Rolls a raw export's leaf-grain groups (reporting.md §13) up to the leaves the axis's dimensions
 * name ({@link LeafLabels}), as the {@link RawTurnoverCell}s and {@link RawBalanceCell}s an
 * ordinary render fetches: sums add, and a transaction counts once per leaf, bucket and currency
 * however many groups it sits in — as the Report's own grouped queries count it. Remembers every
 * leaf it meets.
 */
final class LeafRollup {

  private final AxisPlan axes;
  private final LeafLabels labels;
  private final Map<String, String> labelsByLeafKey = new LinkedHashMap<>();

  LeafRollup(AxisPlan axes, LeafLabels labels) {
    this.axes = axes;
    this.labels = labels;
  }

  /** A group and the leaf it belongs to. */
  private record Placed<T>(LeafLabels.Leaf leaf, T fact) {}

  List<RawTurnoverCell> turnover(List<LeafTurnoverFact> facts) {
    return grouped(
            facts,
            f -> leafOf(f.accountId(), f.tagId(), f.payeeId()),
            p -> List.of(p.leaf().key(), p.fact().bucketKey(), p.fact().currencyCode()))
        .stream()
        .map(LeafRollup::turnoverCell)
        .toList();
  }

  List<RawBalanceCell> balance(List<LeafBalanceFact> facts) {
    return grouped(
            facts,
            f -> leafOf(f.accountId(), null, null),
            p -> List.of(p.leaf().key(), p.fact().currencyCode()))
        .stream()
        .map(LeafRollup::balanceCell)
        .toList();
  }

  /** Every leaf met so far, by key, with its label. */
  Map<String, String> leafLabels() {
    return Map.copyOf(labelsByLeafKey);
  }

  /**
   * {@code facts} placed on their leaves — a group in none dropped — and grouped by {@code key}.
   */
  private static <T> List<List<Placed<T>>> grouped(
      List<T> facts, Function<T, LeafLabels.Leaf> leaf, Function<Placed<T>, List<String>> key) {
    return facts.stream()
        .map(fact -> new Placed<>(leaf.apply(fact), fact))
        .filter(placed -> placed.leaf() != null)
        .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()))
        .values()
        .stream()
        .toList();
  }

  private LeafLabels.Leaf leafOf(long accountId, Long tagId, Long payeeId) {
    LeafLabels.Leaf leaf =
        labels.leaf(axes.nonDateDim(), axes.innerDim(), accountId, tagId, payeeId);
    if (leaf != null) {
      labelsByLeafKey.putIfAbsent(leaf.key(), leaf.label());
    }
    return leaf;
  }

  private static RawTurnoverCell turnoverCell(List<Placed<LeafTurnoverFact>> group) {
    LeafLabels.Leaf leaf = group.get(0).leaf();
    List<LeafTurnoverFact> facts = group.stream().map(Placed::fact).toList();
    LeafTurnoverFact first = facts.get(0);
    return new RawTurnoverCell(
        leaf.key(),
        leaf.label(),
        leaf.type(),
        first.bucketKey(),
        first.currencyCode(),
        sum(facts, LeafTurnoverFact::nativeAmount),
        sum(facts, LeafTurnoverFact::baseAmount),
        facts.stream().mapToLong(LeafTurnoverFact::missingRateCount).sum(),
        facts.stream().mapToLong(LeafTurnoverFact::postingCount).sum(),
        facts.stream().flatMap(f -> f.transactionIds().stream()).distinct().count());
  }

  private static RawBalanceCell balanceCell(List<Placed<LeafBalanceFact>> group) {
    LeafLabels.Leaf leaf = group.get(0).leaf();
    List<LeafBalanceFact> facts = group.stream().map(Placed::fact).toList();
    return new RawBalanceCell(
        leaf.key(),
        leaf.label(),
        leaf.type(),
        facts.get(0).currencyCode(),
        sum(facts, LeafBalanceFact::nativeBalance));
  }

  /** The sum of {@code amount} over {@code facts}; a group whose base had no rate adds nothing. */
  private static <T> BigDecimal sum(List<T> facts, Function<T, BigDecimal> amount) {
    return facts.stream()
        .map(amount)
        .filter(value -> value != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
