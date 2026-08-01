package volkovandr.hauptbuch.categories;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.categories.repository.AiVocabularyRepository;
import volkovandr.hauptbuch.categories.repository.CategoryAiConfigRepository;
import volkovandr.hauptbuch.categories.repository.CategoryAiRow;

/**
 * The AI Vocabulary (data-model §13.3, plan stage 9d): the operator-curated projection of the
 * category taxonomy the receipt parser is allowed to see (ARCH-08). Owned by {@code categories}
 * because keeping the projection consistent through rename / subdivide / delete is this module's
 * job, and because tag resolution (for note-instructed echoes) lives here too.
 *
 * <p>Its two consumer-facing operations are {@link #aiVocabulary()} — the visible, alias-renamed,
 * note-annotated tree (stage 9e feeds it into the prompt) — and {@link #resolveTerm(String)} — the
 * leaves-only, effective-name resolution of an AI's category echo back to a real leaf id. Both are
 * pure reads assembled in Java over the recursive-CTE projection ({@link AiVocabularyRepository});
 * the inheritance/path SQL is SQL-logic-tested, this assembly and matching is unit-tested.
 *
 * <p>The remaining operations back the category-edit UI: {@link #editModel(long)} (the current
 * override + what inherit resolves to), {@link #annotations()} (the categories-list deviation
 * markers), and {@link #saveConfig} (upsert, or delete the row when the operator returns everything
 * to default).
 */
@Service
public class AiVocabularyService {

  /** Both category types project — receipts carry income lines too (data-model §13.3). */
  private static final List<String> TYPES = List.of("income", "expense");

  /** The effective-path separator — the same {@code Parent - Child} the category picker uses. */
  private static final String PATH_SEPARATOR = " - ";

  private static final String EXPENSE = "expense";

  private final AiVocabularyRepository aiVocabularyRepository;
  private final CategoryAiConfigRepository categoryAiConfigRepository;

  AiVocabularyService(
      AiVocabularyRepository aiVocabularyRepository,
      CategoryAiConfigRepository categoryAiConfigRepository) {
    this.aiVocabularyRepository = aiVocabularyRepository;
    this.categoryAiConfigRepository = categoryAiConfigRepository;
  }

  private List<CategoryAiRow> projection() {
    return aiVocabularyRepository.projection(TYPES, PATH_SEPARATOR);
  }

  /** Index the projected rows by account id (preserving order) for ancestor/parent lookups. */
  private static Map<Long, CategoryAiRow> byId(List<CategoryAiRow> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(CategoryAiRow::accountId, r -> r, (a, b) -> a, LinkedHashMap::new));
  }

  // ── Consumer API (stage 9e) ────────────────────────────────────────────────

  /**
   * The AI-facing category tree: every branch that leads to at least one <em>visible leaf</em>,
   * each node under its effective name (alias renamed) with its note attached; hidden leaves and
   * the groups left without a single visible leaf are pruned (data-model §13.3). The forest the
   * parser prompt is built from — never any ledger content.
   */
  public List<AiVocabularyNode> aiVocabulary() {
    List<CategoryAiRow> rows = projection();
    Map<Long, List<CategoryAiRow>> childrenByParent =
        rows.stream()
            .filter(r -> r.parentId() != null)
            .collect(
                Collectors.groupingBy(
                    CategoryAiRow::parentId, LinkedHashMap::new, Collectors.toList()));
    return rows.stream()
        .filter(r -> r.parentId() == null)
        .map(root -> buildNode(root, childrenByParent))
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * Build the vocabulary node for a row, or empty when the branch has nothing visible — a hidden
   * leaf, or a group none of whose descendants are a visible leaf (both pruned).
   */
  private Optional<AiVocabularyNode> buildNode(
      CategoryAiRow row, Map<Long, List<CategoryAiRow>> childrenByParent) {
    List<AiVocabularyNode> children =
        childrenByParent.getOrDefault(row.accountId(), List.of()).stream()
            .map(child -> buildNode(child, childrenByParent))
            .flatMap(Optional::stream)
            .toList();
    boolean visibleLeaf = row.leaf() && row.effVisible();
    if (!visibleLeaf && children.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new AiVocabularyNode(row.effName(), row.type(), row.note(), children));
  }

  /**
   * Resolve an AI's category echo to a real leaf category id (data-model §13.3): leaves-only,
   * case-insensitive, an exact effective {@code Parent - Child} path first, then a <em>unique</em>
   * bare effective leaf name. A group, a hidden leaf, the real name of an aliased node, an
   * ambiguous name, or an unknown term all resolve empty — the caller seeds the line uncategorised.
   */
  public OptionalLong resolveTerm(String text) {
    if (text == null || text.isBlank()) {
      return OptionalLong.empty();
    }
    String needle = text.strip();
    List<CategoryAiRow> visibleLeaves =
        projection().stream().filter(r -> r.leaf() && r.effVisible()).toList();

    List<CategoryAiRow> byPath =
        visibleLeaves.stream().filter(r -> r.effPath().equalsIgnoreCase(needle)).toList();
    if (byPath.size() == 1) {
      return OptionalLong.of(byPath.get(0).accountId());
    }
    if (byPath.size() > 1) {
      return OptionalLong.empty(); // an ambiguous path never guesses
    }
    List<CategoryAiRow> byName =
        visibleLeaves.stream().filter(r -> r.effName().equalsIgnoreCase(needle)).toList();
    return byName.size() == 1 ? OptionalLong.of(byName.get(0).accountId()) : OptionalLong.empty();
  }

  // ── Category-edit UI ───────────────────────────────────────────────────────

  /**
   * The AI-parsing section state for one category: its own override, plus what "Inherit" currently
   * resolves to (the nearest set ancestor, else the type default) so the radio can spell it out.
   *
   * @throws IllegalArgumentException if the id is not a projected category node
   */
  public AiConfigEditModel editModel(long accountId) {
    Map<Long, CategoryAiRow> byId = byId(projection());
    CategoryAiRow node = byId.get(accountId);
    if (node == null) {
      throw new IllegalArgumentException("No AI-vocabulary category with id " + accountId);
    }
    CategoryAiRow parent = node.parentId() == null ? null : byId.get(node.parentId());
    boolean inheritedVisible = parent != null ? parent.effVisible() : EXPENSE.equals(node.type());
    String inheritedSource = inheritedSource(node, parent, byId);
    return new AiConfigEditModel(
        node.ownVisible(), node.alias(), node.note(), inheritedVisible, inheritedSource);
  }

  /**
   * The phrase describing where the inherited (own-flag-ignored) visibility comes from: the nearest
   * set ancestor above this node, or the type default when none sets one.
   */
  private String inheritedSource(
      CategoryAiRow node, CategoryAiRow parent, Map<Long, CategoryAiRow> byId) {
    if (parent == null || parent.visibleSourceId() == null) {
      return "the type default";
    }
    Long sourceId = parent.visibleSourceId();
    String sourceName = byId.get(sourceId).name();
    boolean sourceIsDirectParent = sourceId.equals(node.parentId());
    return "via " + (sourceIsDirectParent ? "parent " : "") + "'" + sourceName + "'";
  }

  /**
   * The categories-list annotations, keyed by account id — one entry per node that has something to
   * show (a visibility deviation from the type default, an alias, or a note). Rows on the default
   * with no alias or note are absent, so the list stays clean.
   */
  public Map<Long, CategoryAiAnnotation> annotations() {
    List<CategoryAiRow> rows = projection();
    Map<Long, CategoryAiRow> byId = byId(rows);
    return rows.stream()
        .map(row -> Map.entry(row.accountId(), annotationFor(row, byId)))
        .filter(entry -> entry.getValue().hasContent())
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  /** The list annotation for one node: its deviation, alias, and note (data-model §13.3). */
  private CategoryAiAnnotation annotationFor(CategoryAiRow row, Map<Long, CategoryAiRow> byId) {
    boolean deviates = row.effVisible() != EXPENSE.equals(row.type());
    boolean setHere = row.ownVisible() != null;
    String viaName =
        deviates && !setHere && row.visibleSourceId() != null
            ? byId.get(row.visibleSourceId()).name()
            : null;
    return new CategoryAiAnnotation(
        row.effVisible(), deviates, setHere, viaName, row.alias(), row.note());
  }

  /**
   * Save a category's AI config from the edit form. Blank alias/note normalise to {@code null};
   * when the operator leaves everything at default — inherit visibility, no alias, no note — the
   * row is <em>deleted</em> rather than stored (absence means "inherit everything", data-model
   * §13.3), keeping the table to only the curated rows.
   *
   * @param accountId the category being edited (the controller has already checked it is
   *     manageable)
   * @param visible the chosen tri-state ({@code null} = inherit)
   * @param alias the typed alias, possibly blank
   * @param note the typed AI note, possibly blank
   */
  @Transactional
  public void saveConfig(long accountId, Boolean visible, String alias, String note) {
    String normalizedAlias = blankToNull(alias);
    String normalizedNote = blankToNull(note);
    if (visible == null && normalizedAlias == null && normalizedNote == null) {
      categoryAiConfigRepository.deleteByAccountId(accountId);
      return;
    }
    categoryAiConfigRepository.upsert(accountId, visible, normalizedAlias, normalizedNote);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
