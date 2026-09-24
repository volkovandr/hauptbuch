package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link AutoExpansion}'s {@code auto} row-expansion rule (reporting.md
 * §9.2) — a pure function of the spec's own filter, decidable without the DB.
 */
class AutoExpansionTest {

  private static final DateRange A_RANGE =
      new DateRange(
          new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
          new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)));

  private static ReportSpec specWithFilters(List<ReportFilter> filters) {
    return new ReportSpec(
        List.of(Dimension.TAG, Dimension.CATEGORY),
        List.of(),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        filters,
        A_RANGE,
        false,
        false,
        true);
  }

  @Test
  void autoExpandedKeysNamesOnlyTheOneFilteredNodeNotEveryCandidate() {
    // reporting.md §9.2's "you asked about one thing; show its parts" names the one thing the
    // filter selected — not, as an earlier implementation wrongly read it, every other top-level
    // node of the same dimension too (the owner's "tags always appear fully expanded" report).
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.TAG, FilterLevel.TRANSACTION, FilterOperator.IS_ONE_OF, List.of("1")));

    assertThat(AutoExpansion.autoExpandedKeys(Dimension.TAG, specWithFilters(filters)))
        .containsExactly("1");
  }

  @Test
  void autoExpandedKeysIsEmptyWhenThereIsNoMatchingFilter() {
    assertThat(AutoExpansion.autoExpandedKeys(Dimension.TAG, specWithFilters(List.of()))).isEmpty();
  }

  @Test
  void autoExpandedKeysIsEmptyWhenTheFilterSelectsTwoOrMoreNodes() {
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.TAG,
                FilterLevel.TRANSACTION,
                FilterOperator.IS_ONE_OF,
                List.of("1", "2")));

    assertThat(AutoExpansion.autoExpandedKeys(Dimension.TAG, specWithFilters(filters))).isEmpty();
  }

  @Test
  void autoExpandedKeysIsEmptyWhenTheOnlyMatchingFilterIsOnDifferentField() {
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.CATEGORY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of("1")));

    assertThat(AutoExpansion.autoExpandedKeys(Dimension.TAG, specWithFilters(filters))).isEmpty();
  }

  @Test
  void autoExpandedKeysNeverExpandsFlatDimensionEvenWithOneNodeFilter() {
    List<ReportFilter> filters =
        List.of(
            new ReportFilter(
                FilterField.PAYEE,
                FilterLevel.TRANSACTION,
                FilterOperator.IS_ONE_OF,
                List.of("1")));

    assertThat(AutoExpansion.autoExpandedKeys(Dimension.PAYEE, specWithFilters(filters))).isEmpty();
  }

  @Test
  void isNestableIsTrueOnlyForTheThreeHierarchicalDimensions() {
    assertThat(AutoExpansion.isNestable(Dimension.CATEGORY)).isTrue();
    assertThat(AutoExpansion.isNestable(Dimension.ACCOUNT)).isTrue();
    assertThat(AutoExpansion.isNestable(Dimension.TAG)).isTrue();
    assertThat(AutoExpansion.isNestable(Dimension.PAYEE)).isFalse();
    assertThat(AutoExpansion.isNestable(Dimension.PERSON)).isFalse();
    assertThat(AutoExpansion.isNestable(Dimension.CURRENCY)).isFalse();
    assertThat(AutoExpansion.isNestable(Dimension.ACCOUNT_TYPE)).isFalse();
    assertThat(AutoExpansion.isNestable(Dimension.DATE)).isFalse();
    assertThat(AutoExpansion.isNestable(null)).isFalse();
  }
}
