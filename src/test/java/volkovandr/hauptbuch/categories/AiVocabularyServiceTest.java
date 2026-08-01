package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.categories.repository.AiVocabularyRepository;
import volkovandr.hauptbuch.categories.repository.CategoryAiConfigRepository;
import volkovandr.hauptbuch.categories.repository.CategoryAiRow;

/**
 * Unit tier (plan §1.5): {@link AiVocabularyService}'s Java assembly over the projection — tree
 * pruning ({@link AiVocabularyService#aiVocabulary()}), leaves-only name resolution ({@link
 * AiVocabularyService#resolveTerm}), the list annotations, the edit model, and the save/delete
 * decision. The recursive-CTE projection it consumes is mocked; the SQL itself is SQL-logic-tested
 * (data-model §13.3).
 */
@ExtendWith(MockitoExtension.class)
class AiVocabularyServiceTest {

  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String SEP = " - ";

  @Mock private AiVocabularyRepository aiVocabularyRepository;
  @Mock private CategoryAiConfigRepository categoryAiConfigRepository;
  @InjectMocks private AiVocabularyService service;

  /**
   * A projected row. The fixtures stay consistent with what the real CTE produces, so two fields
   * can be derived rather than passed:
   *
   * <ul>
   *   <li>the effective (and, for the un-aliased source nodes any test reads, the real) name is the
   *       last segment of {@code effPath}; and
   *   <li>a node's own flag is set exactly when it is its own visibility source ({@code sourceId ==
   *       id}), and then equals its effective visibility — so {@code ownVisible} follows from
   *       {@code sourceId} and {@code effVisible}.
   * </ul>
   */
  private static CategoryAiRow row(
      long id,
      String type,
      Long parentId,
      boolean leaf,
      boolean effVisible,
      Long sourceId,
      String alias,
      String note,
      String effPath) {
    int lastSeparator = effPath.lastIndexOf(SEP);
    String effName = lastSeparator < 0 ? effPath : effPath.substring(lastSeparator + SEP.length());
    Boolean ownVisible = sourceId != null && sourceId == id ? effVisible : null;
    return new CategoryAiRow(
        id,
        effName,
        type,
        parentId,
        leaf,
        ownVisible,
        effVisible,
        sourceId,
        alias,
        note,
        effName,
        effPath);
  }

  private void projection(CategoryAiRow... rows) {
    when(aiVocabularyRepository.projection(any(), anyString())).thenReturn(List.of(rows));
  }

  // ── aiVocabulary() ──────────────────────────────────────────────────────────

  @Test
  void prunesHiddenLeavesAndGroupsWithNoVisibleLeaf() {
    // Food (group) with a visible Milk and a hidden Sweets; Salary (income) hidden by default.
    projection(
        row(1, EXPENSE, null, false, true, null, null, null, "Food"),
        row(2, EXPENSE, 1L, true, true, null, null, null, "Food - Milk"),
        row(3, EXPENSE, 1L, true, false, 3L, null, null, "Food - Sweets"),
        row(4, INCOME, null, true, false, null, null, null, "Salary"));

    List<AiVocabularyNode> tree = service.aiVocabulary();

    assertThat(tree).hasSize(1); // Salary pruned (hidden), Food kept for Milk
    AiVocabularyNode food = tree.get(0);
    assertThat(food.name()).isEqualTo("Food");
    assertThat(food.children()).extracting(AiVocabularyNode::name).containsExactly("Milk");
  }

  @Test
  void keepsAnOverriddenVisibleLeafUnderHiddenGroupAndAppliesAliasesAndNotes() {
    // Food is an alias (real name irrelevant); its effPath uses the alias.
    projection(
        row(1, EXPENSE, null, false, false, 1L, "Food", "prefer specifics", "Food"),
        row(2, EXPENSE, 1L, true, true, 2L, null, "M&Ms → for Bobby", "Food - Sweets"));

    List<AiVocabularyNode> tree = service.aiVocabulary();

    AiVocabularyNode food = tree.get(0);
    assertThat(food.name()).isEqualTo("Food"); // the alias
    assertThat(food.note()).isEqualTo("prefer specifics");
    assertThat(food.children()).hasSize(1);
    assertThat(food.children().get(0).name()).isEqualTo("Sweets");
    assertThat(food.children().get(0).note()).isEqualTo("M&Ms → for Bobby");
  }

  // ── resolveTerm() ─────────────────────────────────────────────────────────

  @Test
  void resolvesAnExactEffectivePathCaseInsensitively() {
    projection(
        row(1, EXPENSE, null, false, true, null, null, null, "Food"),
        row(2, EXPENSE, 1L, true, true, null, null, null, "Food - Milk"));

    assertThat(service.resolveTerm("food - milk")).hasValue(2L);
  }

  @Test
  void resolvesUniqueBareEffectiveLeafName() {
    projection(
        row(1, EXPENSE, null, false, true, null, null, null, "Food"),
        row(2, EXPENSE, 1L, true, true, null, null, null, "Food - Milk"));

    assertThat(service.resolveTerm("MILK")).hasValue(2L);
  }

  @Test
  void prefersTheExactPathOverAnAmbiguousBareName() {
    projection(
        row(1, EXPENSE, null, false, true, null, null, null, "Home"),
        row(2, EXPENSE, 1L, true, true, null, null, null, "Home - Other"),
        row(3, EXPENSE, null, false, true, null, null, null, "Work"),
        row(4, EXPENSE, 3L, true, true, null, null, null, "Work - Other"));

    // Bare "Other" is ambiguous, but the full path pins it.
    assertThat(service.resolveTerm("Work - Other")).hasValue(4L);
    assertThat(service.resolveTerm("Other")).isEmpty();
  }

  @Test
  void doesNotResolveGroupsHiddenLeavesTheRealNameOfAnAliasOrUnknowns() {
    // Row 1 is aliased to "Food"; resolution matches only effective names, never the real one.
    projection(
        row(1, EXPENSE, null, false, true, null, "Food", null, "Food"),
        row(2, EXPENSE, 1L, true, true, null, null, null, "Food - Milk"),
        row(3, EXPENSE, 1L, true, false, 3L, null, null, "Food - Sweets"));

    assertThat(service.resolveTerm("Food")).isEmpty(); // a group
    assertThat(service.resolveTerm("Sweets")).isEmpty(); // a hidden leaf
    assertThat(service.resolveTerm("Groceries")).isEmpty(); // the real name behind the alias
    assertThat(service.resolveTerm("Nonexistent")).isEmpty();
    assertThat(service.resolveTerm("  ")).isEmpty();
  }

  // ── annotations() ─────────────────────────────────────────────────────────

  @Test
  void annotatesDeviationsAndVisibleAliasesButNotHiddenOnes() {
    projection(
        // Plain default expense leaf → no annotation.
        row(1, EXPENSE, null, true, true, null, null, null, "Rent"),
        // Hidden expense group, set here → deviation.
        row(2, EXPENSE, null, false, false, 2L, null, null, "Food"),
        // Child inherits the hidden group → deviation via 'Food'.
        row(3, EXPENSE, 2L, true, false, 2L, null, null, "Food - Milk"),
        // Visible expense leaf with an alias → annotated for the alias (no deviation).
        row(4, EXPENSE, null, true, true, null, "Groceries", null, "Groceries"),
        // Hidden income leaf with an alias → the alias never reaches the AI, so NOT annotated.
        row(5, INCOME, null, true, false, null, "Wages", null, "Wages"));

    Map<Long, CategoryAiAnnotation> annotations = service.annotations();

    assertThat(annotations).doesNotContainKey(1L);
    assertThat(annotations.get(2L).deviates()).isTrue();
    assertThat(annotations.get(2L).setHere()).isTrue();
    assertThat(annotations.get(2L).visible()).isFalse();
    assertThat(annotations.get(3L).deviates()).isTrue();
    assertThat(annotations.get(3L).setHere()).isFalse();
    assertThat(annotations.get(3L).viaName()).isEqualTo("Food");
    // Visible alias shows; deviation is false.
    assertThat(annotations.get(4L).deviates()).isFalse();
    assertThat(annotations.get(4L).alias()).isEqualTo("Groceries");
    // Hidden category with only an alias → suppressed entirely.
    assertThat(annotations).doesNotContainKey(5L);
  }

  // ── editModel() ───────────────────────────────────────────────────────────

  @Test
  void editModelSpellsOutInheritanceFromTheTypeDefault() {
    projection(row(1, EXPENSE, null, true, true, null, null, null, "Rent"));

    AiConfigEditModel model = service.editModel(1L);

    assertThat(model.visible()).isNull();
    assertThat(model.inheritedVisible()).isTrue();
    assertThat(model.inheritedSource()).isEqualTo("the type default");
  }

  @Test
  void editModelSpellsOutInheritanceFromSetParent() {
    projection(
        row(1, EXPENSE, null, false, false, 1L, null, null, "Food"),
        row(2, EXPENSE, 1L, true, false, 1L, null, "note", "Food - Milk"));

    AiConfigEditModel model = service.editModel(2L);

    assertThat(model.note()).isEqualTo("note");
    assertThat(model.inheritedVisible()).isFalse();
    assertThat(model.inheritedSource()).isEqualTo("via parent 'Food'");
  }

  @Test
  void editModelRejectsAnUnknownCategory() {
    projection();
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.editModel(99L));
  }

  // ── saveConfig() ──────────────────────────────────────────────────────────

  @Test
  void savingAllDefaultsDeletesTheRow() {
    service.saveConfig(5L, null, "  ", "");

    verify(categoryAiConfigRepository).deleteByAccountId(5L);
    verify(categoryAiConfigRepository, never()).upsert(any(Long.class), any(), any(), any());
  }

  @Test
  void savingAnyOverrideUpsertsWithBlanksNormalisedToNull() {
    service.saveConfig(5L, Boolean.FALSE, "  Groceries  ", "  ");

    verify(categoryAiConfigRepository).upsert(eq(5L), eq(Boolean.FALSE), eq("Groceries"), isNull());
    verify(categoryAiConfigRepository, never()).deleteByAccountId(5L);
  }
}
