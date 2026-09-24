package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Unit tier (CLAUDE.md §6): {@link AxisCandidates} — which {@link ReportQueryRepository} candidate
 * lookup a dimension maps to, the ticked nodes a filter on its own field promotes (reporting issue
 * 08), and stage e's parent-key resolution for child candidates — with the repository mocked. The
 * SQL itself is {@code ReportQuerySqlLogicTest}'s job.
 */
class AxisCandidatesTest {

  private final ReportQueryRepository queryRepository = mock();
  private final AxisCandidates axisCandidates = new AxisCandidates(queryRepository);

  private static ReportSpec turnoverSpec(Dimension dim) {
    return filteredSpec(dim, Scope.ofTypes("expense"), null);
  }

  private static ReportSpec filteredSpec(Dimension dim, Scope scope, ReportFilter filter) {
    return new ReportSpec(
        dim == null ? List.of() : List.of(dim),
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        scope,
        filter == null ? List.of() : List.of(filter),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  private static ReportFilter ownFilter(FilterField field, FilterLevel level, String... ids) {
    return new ReportFilter(field, level, FilterOperator.IS_ONE_OF, List.of(ids));
  }

  // ── candidatesFor ─────────────────────────────────────────────────────────

  @Test
  void categoryAndAccountDimensionsFetchTopLevelAccounts() {
    when(queryRepository.topLevelAccounts(List.of("expense"), true))
        .thenReturn(List.of(new TopLevelNode("1", "Food", "expense")));

    Map<String, TopLevelNode> candidates =
        axisCandidates.candidatesFor(Dimension.CATEGORY, turnoverSpec(Dimension.CATEGORY));

    assertThat(candidates).containsOnlyKeys("1");
  }

  @Test
  void tagDimensionFetchesTopLevelTags() {
    when(queryRepository.topLevelTags()).thenReturn(List.of(new TopLevelNode("1", "Car", null)));

    assertThat(axisCandidates.candidatesFor(Dimension.TAG, turnoverSpec(Dimension.TAG)))
        .containsOnlyKeys("1");
  }

  @Test
  void noDimensionFetchesNoCandidates() {
    assertThat(axisCandidates.candidatesFor(null, turnoverSpec(null))).isEmpty();
  }

  // ── candidatesFor: a filter on the dimension's own field (reporting issue 08) ────────────

  @Test
  void bookedToFilterOnOwnFieldListsOnlyTheTickedNodes() {
    when(queryRepository.promotedAccountCandidates(List.of(5L, 7L), List.of("expense"), true))
        .thenReturn(
            List.of(
                new TopLevelNode("7", "Food:Bakery", "expense"),
                new TopLevelNode("5", "Rent", "expense")));
    ReportSpec spec =
        filteredSpec(
            Dimension.CATEGORY,
            Scope.ofTypes("expense"),
            ownFilter(FilterField.CATEGORY, FilterLevel.POSTING, "5", "7"));

    assertThat(axisCandidates.candidatesFor(Dimension.CATEGORY, spec)).containsOnlyKeys("7", "5");
    verify(queryRepository, never()).topLevelAccounts(anyList(), anyBoolean());
  }

  @Test
  void touchingFilterOnOwnFieldAddsTheRealRootsInLabelOrder() {
    // The real roots only stand in for the nodes a qualifying transaction may touch; ReportEngine
    // drops the ones that turn out untouched once the data is in.
    when(queryRepository.promotedAccountCandidates(List.of(12L), List.of("asset"), true))
        .thenReturn(List.of(new TopLevelNode("12", "Cash:Cash-EUR", "asset")));
    when(queryRepository.topLevelAccounts(List.of("asset"), true))
        .thenReturn(
            List.of(
                new TopLevelNode("20", "BankBbb", "asset"),
                new TopLevelNode("10", "Cash", "asset")));
    ReportSpec spec =
        filteredSpec(
            Dimension.ACCOUNT,
            Scope.ofTypes("asset"),
            ownFilter(FilterField.ACCOUNT, FilterLevel.TRANSACTION, "12"));

    assertThat(axisCandidates.candidatesFor(Dimension.ACCOUNT, spec).keySet())
        .containsExactly("20", "10", "12");
  }

  @Test
  void filterOnAnotherFieldPromotesNothing() {
    ReportSpec spec =
        filteredSpec(
            Dimension.CATEGORY,
            Scope.ofTypes("expense"),
            ownFilter(FilterField.ACCOUNT, FilterLevel.TRANSACTION, "12"));

    axisCandidates.candidatesFor(Dimension.CATEGORY, spec);

    verify(queryRepository).topLevelAccounts(List.of("expense"), true);
    verify(queryRepository, never()).promotedAccountCandidates(anyList(), anyList(), anyBoolean());
  }

  @Test
  void accountDimensionListsOnlyAccountTypesWhateverTheScopeAlsoHolds() {
    // reporting.md §4: Account covers asset/liability/equity — an expense scope never turns
    // categories into "accounts".
    ReportSpec spec = filteredSpec(Dimension.ACCOUNT, Scope.ofTypes("asset", "expense"), null);

    axisCandidates.candidatesFor(Dimension.ACCOUNT, spec);

    verify(queryRepository).topLevelAccounts(List.of("asset"), true);
  }

  @Test
  void accountDimensionWithNoAccountTypeInScopeHasNoCandidatesAndQueriesNothing() {
    ReportSpec spec = filteredSpec(Dimension.ACCOUNT, Scope.ofTypes("expense"), null);

    assertThat(axisCandidates.candidatesFor(Dimension.ACCOUNT, spec)).isEmpty();
    verify(queryRepository, never()).topLevelAccounts(anyList(), anyBoolean());
  }

  // ── childCandidatesFor (stage e's parent-key resolution) ─────────────────

  @Test
  void childCandidatesForUsesTheWholeKeyAsTheParentIdForTopLevelNode() {
    axisCandidates.childCandidatesFor(Dimension.CATEGORY, "5", turnoverSpec(Dimension.CATEGORY));

    verify(queryRepository).childAccountCandidates(5L, true, List.of());
  }

  @Test
  void childCandidatesForUsesLastSegmentOfCompositeKeyAsTheParentId() {
    axisCandidates.childCandidatesFor(Dimension.CATEGORY, "1|10", turnoverSpec(Dimension.CATEGORY));

    verify(queryRepository).childAccountCandidates(10L, true, List.of());
  }

  @Test
  void childCandidatesForDegradesToNoChildrenForMalformedTrailingSegmentInsteadOfThrowing() {
    // A stage e2 toggle endpoint's `node` request param, or a persisted expandedNodeKeys entry, is
    // hand-editable request input — a malformed one (or Tag's own non-numeric "<id>:unspecified"
    // leaf key, which is never itself expandable and so should never legitimately arrive here as a
    // parent) must degrade to "no children", not throw and crash the whole render.
    List<TopLevelNode> children =
        axisCandidates.childCandidatesFor(
            Dimension.CATEGORY, "1|abc", turnoverSpec(Dimension.CATEGORY));

    assertThat(children).isEmpty();
    verify(queryRepository).childAccountCandidates(-1L, true, List.of());
  }

  @Test
  void childCandidatesForOnTagDimensionAlsoDegradesGracefullyForMalformedTrailingSegment() {
    List<TopLevelNode> children =
        axisCandidates.childCandidatesFor(
            Dimension.TAG, "5:unspecified", turnoverSpec(Dimension.TAG));

    assertThat(children).isEmpty();
    verify(queryRepository).childTagCandidates(-1L, List.of());
  }

  @Test
  void childCandidatesLeaveOutThePromotedNodes() {
    ReportSpec spec =
        filteredSpec(
            Dimension.ACCOUNT,
            Scope.ofTypes("asset"),
            ownFilter(FilterField.ACCOUNT, FilterLevel.TRANSACTION, "12"));

    axisCandidates.childCandidatesFor(Dimension.ACCOUNT, "10", spec);

    verify(queryRepository).childAccountCandidates(10L, true, List.of(12L));
  }

  // ── the personal-debt tree (reporting issues 06, 11) ─────────────────────

  @Test
  void personalDebtsChildrenArePeopleAndPersonsChildrenAreTheirLeaves() {
    ReportSpec spec = filteredSpec(Dimension.ACCOUNT, Scope.ofTypes("asset"), null);
    List<TopLevelNode> people = List.of(new TopLevelNode("person:7", "Max", "asset", true));
    List<TopLevelNode> leaves = List.of(new TopLevelNode("31", "EUR", "asset", false));
    when(queryRepository.debtPeopleCandidates(true)).thenReturn(people);
    when(queryRepository.debtLeafCandidates(7L, true)).thenReturn(leaves);

    assertThat(axisCandidates.childCandidatesFor(Dimension.ACCOUNT, "personal", spec))
        .isEqualTo(people);
    assertThat(axisCandidates.childCandidatesFor(Dimension.ACCOUNT, "personal|person:7", spec))
        .isEqualTo(leaves);
  }

  @Test
  void tickedPersonalDebtsIsPromotedToTheTopLevel() {
    ReportSpec spec =
        filteredSpec(
            Dimension.ACCOUNT,
            Scope.ofTypes("asset"),
            ownFilter(FilterField.ACCOUNT, FilterLevel.POSTING, "personal", "12"));
    TopLevelNode personalDebts = new TopLevelNode("personal", "Personal debts", "asset", true);
    when(queryRepository.promotedAccountCandidates(List.of(12L), List.of("asset"), true))
        .thenReturn(List.of(new TopLevelNode("12", "Cash:Cash-EUR", "asset")));
    when(queryRepository.topLevelAccounts(List.of("asset"), true))
        .thenReturn(List.of(new TopLevelNode("1", "Cash", "asset", true), personalDebts));

    assertThat(axisCandidates.candidatesFor(Dimension.ACCOUNT, spec).values())
        .extracting(TopLevelNode::key)
        .containsExactly("12", "personal");
  }
}
