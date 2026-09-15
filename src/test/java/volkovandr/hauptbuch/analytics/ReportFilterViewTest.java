package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.analytics.ReportFilterView.Candidate;
import volkovandr.hauptbuch.analytics.ReportFilterView.NodeRow;
import volkovandr.hauptbuch.analytics.ReportFilterView.OptionRow;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportFilterView}'s pure row-building logic (reporting.md
 * §11a.5, plan stage d3-4) — no DB, so the node-storage/subtree-cascade rule and the per-section
 * hidden-field surgery are tested without a container. The DB-touching half ({@link
 * ReportFilterViewAssembler}) is covered by {@code ReportControllerIntegrationTest}.
 */
class ReportFilterViewTest {

  @Test
  void tickingParentRendersItsChildTickedAndDisabledButNotItself() {
    Candidate food = new Candidate(1, null, "Food", 0);
    Candidate bakery = new Candidate(2, 1L, "Bakery", 1);
    Candidate cash = new Candidate(3, null, "Cash", 0);

    List<NodeRow> rows = ReportFilterView.nodeRows(List.of(food, bakery, cash), List.of("1"));

    NodeRow foodRow = rows.get(0);
    assertThat(foodRow.ticked()).isTrue();
    assertThat(foodRow.disabled()).isFalse();
    NodeRow bakeryRow = rows.get(1);
    assertThat(bakeryRow.ticked()).isTrue();
    assertThat(bakeryRow.disabled()).isTrue();
    assertThat(bakeryRow.ancestors()).isEqualTo("1");
    NodeRow cashRow = rows.get(2);
    assertThat(cashRow.ticked()).isFalse();
    assertThat(cashRow.disabled()).isFalse();
  }

  @Test
  void grandchildIsCoveredByTickedGrandparentNotJustItsImmediateParent() {
    Candidate car = new Candidate(1, null, "Car", 0);
    Candidate audi = new Candidate(2, 1L, "Audi", 1);
    Candidate a4 = new Candidate(3, 2L, "A4", 2);

    List<NodeRow> rows = ReportFilterView.nodeRows(List.of(car, audi, a4), List.of("1"));

    NodeRow a4Row = rows.get(2);
    assertThat(a4Row.ticked()).isTrue();
    assertThat(a4Row.disabled()).isTrue();
    assertThat(a4Row.ancestors()).isEqualTo("1 2");
  }

  @Test
  void anExplicitlyTickedNodeUnderAnUnrelatedRootStaysIndependentlyTicked() {
    Candidate food = new Candidate(1, null, "Food", 0);
    Candidate bakery = new Candidate(2, 1L, "Bakery", 1);

    // Only the child is ticked — the parent must not be forced by its own descendant.
    List<NodeRow> rows = ReportFilterView.nodeRows(List.of(food, bakery), List.of("2"));

    assertThat(rows.get(0).ticked()).isFalse();
    assertThat(rows.get(1).ticked()).isTrue();
    assertThat(rows.get(1).disabled()).isFalse();
  }

  @Test
  void optionRowsTicksExactlyTheGivenValues() {
    List<OptionRow> rows =
        ReportFilterView.optionRows(
            List.of("asset", "expense"), List.of("Asset", "Expense"), List.of("expense"));

    assertThat(rows)
        .extracting(OptionRow::value, OptionRow::ticked)
        .containsExactly(tuple("asset", false), tuple("expense", true));
  }

  @Test
  void withoutFilterDropsOnlyItsOwnFieldsEntryAndKeepsOtherActiveFields() {
    MultiValueMap<String, String> all = new LinkedMultiValueMap<>();
    all.add("filterField", "CATEGORY");
    all.add("filterField", "PERSON");
    all.add("filter.CATEGORY.level", "POSTING");
    all.add("filter.CATEGORY.op", "IS_ONE_OF");
    all.add("filter.CATEGORY.value", "1");
    all.add("filter.PERSON.level", "TRANSACTION");
    all.add("filter.PERSON.op", "IS_ONE_OF");
    all.add("filter.PERSON.value", "7");
    all.add("measure", "COUNT_POSTINGS");

    MultiValueMap<String, String> result =
        ReportFilterView.withoutFilter(all, FilterField.CATEGORY);

    assertThat(result.get("filterField")).containsExactly("PERSON");
    assertThat(result)
        .doesNotContainKeys("filter.CATEGORY.level", "filter.CATEGORY.op", "filter.CATEGORY.value");
    assertThat(result.getFirst("filter.PERSON.value")).isEqualTo("7");
    assertThat(result.getFirst("measure")).isEqualTo("COUNT_POSTINGS");
  }

  @Test
  void withoutFilterRemovesTheWholeFilterFieldKeyWhenItWasTheOnlyOneActive() {
    MultiValueMap<String, String> all = new LinkedMultiValueMap<>();
    all.add("filterField", "NOTE");
    all.add("filter.NOTE.level", "TRANSACTION");
    all.add("filter.NOTE.op", "CONTAINS");
    all.add("filter.NOTE.value", "fuel");

    MultiValueMap<String, String> result = ReportFilterView.withoutFilter(all, FilterField.NOTE);

    assertThat(result).doesNotContainKey("filterField");
  }
}
