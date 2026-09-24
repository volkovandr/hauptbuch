package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Unit tier (CLAUDE.md §6): {@link PromotedNodes} — which filter promotes a dimension's ticked
 * nodes to its axis's top level (reporting issue 08), the promoted ids the tree queries receive,
 * and which listed real roots survive under "touching".
 */
class PromotedNodesTest {

  private static ReportSpec spec(List<Dimension> rows, ReportFilter... filters) {
    return new ReportSpec(
        rows,
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("asset", "expense"),
        List.of(filters),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  private static ReportFilter filter(FilterField field, FilterLevel level, String... ids) {
    return new ReportFilter(field, level, FilterOperator.IS_ONE_OF, List.of(ids));
  }

  @Test
  void onlyFilterOnTheDimensionsOwnFieldPromotes() {
    ReportSpec spec =
        spec(
            List.of(Dimension.CATEGORY),
            filter(FilterField.ACCOUNT, FilterLevel.TRANSACTION, "12"),
            filter(FilterField.CATEGORY, FilterLevel.POSTING, "7"));

    assertThat(PromotedNodes.ids(Dimension.CATEGORY, spec)).containsExactly(7L);
    assertThat(PromotedNodes.ids(Dimension.ACCOUNT, spec)).containsExactly(12L);
    assertThat(PromotedNodes.ids(Dimension.PAYEE, spec)).isEmpty();
  }

  @Test
  void constraintsCarryEachTreesPromotedIdsForBothDimensionsOfTheAxis() {
    ReportSpec spec =
        spec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            filter(FilterField.TAG, FilterLevel.TRANSACTION, "3"),
            filter(FilterField.CATEGORY, FilterLevel.POSTING, "7"));

    QueryConstraints constraints =
        PromotedNodes.constraints(spec.filters(), spec, Dimension.TAG, Dimension.CATEGORY);

    assertThat(constraints.promotedAccountIds()).containsExactly(7L);
    assertThat(constraints.promotedTagIds()).containsExactly(3L);
  }

  @Test
  void touchingKeepsTickedNodesAndTouchedRootsOnly() {
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT), filter(FilterField.ACCOUNT, FilterLevel.TRANSACTION, "12"));
    Map<String, TopLevelNode> listed =
        Map.of(
            "12", new TopLevelNode("12", "Cash:Cash-EUR", "asset"),
            "20", new TopLevelNode("20", "BankBbb", "asset"),
            "30", new TopLevelNode("30", "BankCcc", "asset"));

    assertThat(
            PromotedNodes.withoutUntouchedRoots(
                Dimension.ACCOUNT, spec, listed, Set.of("20")::contains))
        .containsOnlyKeys("12", "20");
  }

  @Test
  void withoutFilterOnTheOwnFieldNothingIsDropped() {
    Map<String, TopLevelNode> listed = Map.of("30", new TopLevelNode("30", "BankCcc", "asset"));

    assertThat(
            PromotedNodes.withoutUntouchedRoots(
                Dimension.ACCOUNT, spec(List.of(Dimension.ACCOUNT)), listed, key -> false))
        .containsOnlyKeys("30");
  }
}
