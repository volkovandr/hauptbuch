package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.categories.repository.AiVocabularyRepository;
import volkovandr.hauptbuch.categories.repository.CategoryAiRow;

/**
 * SQL-logic tier (plan §1.5): the recursive-CTE projection on {@link AiVocabularyRepository} — the
 * effective-visibility carry-down (own flag → nearest set ancestor → type default), the
 * source-account tracking, the alias-renamed effective path, semantic-leaf detection, and the
 * currency-leaf exclusion (data-model §13.3, §6.5). This is logic that lives in the SQL, so it is
 * exercised here with crafted trees rather than in the integration tier's row round-trips.
 *
 * <p>Boots a Spring context so the query under test is the real repository SQL; raw {@link
 * JdbcClient} seeds trees and configs. {@code @Transactional} rolls each test back on the reused
 * container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AiVocabularyProjectionSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String SEPARATOR = " - ";
  private static final List<String> TYPES = List.of(INCOME, EXPENSE);

  @Autowired JdbcClient jdbcClient;
  @Autowired AiVocabularyRepository repository;

  private long insertRoot(String name, String type) {
    return insert(name, type, null, false);
  }

  private long insertChild(String name, String type, long parentId) {
    return insert(name, type, parentId, false);
  }

  private long insertCurrencyLeaf(String name, String type, long parentId) {
    return insert(name, type, parentId, true);
  }

  private long insert(String name, String type, Long parentId, boolean currencyLeaf) {
    return jdbcClient
        .sql(
            "insert into account (name, type, parent_id, currency_code, currency_leaf)"
                + " values (:n, :t, :p, :c, :cl) returning account_id")
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .param("c", EUR)
        .param("cl", currencyLeaf)
        .query(Long.class)
        .single();
  }

  private void config(long accountId, Boolean visible, String alias, String aiNote) {
    jdbcClient
        .sql(
            "insert into category_ai_config (account_id, visible, alias, ai_note)"
                + " values (:a, :v, :al, :n)")
        .param("a", accountId)
        .param("v", visible)
        .param("al", alias)
        .param("n", aiNote)
        .update();
  }

  private Map<Long, CategoryAiRow> projectionById() {
    return repository.projection(TYPES, SEPARATOR).stream()
        .collect(java.util.stream.Collectors.toMap(CategoryAiRow::accountId, Function.identity()));
  }

  @Test
  void typeDefaultsApplyWithNoConfig() {
    long groceries = insertRoot("Groceries", EXPENSE);
    long salary = insertRoot("Salary", INCOME);

    Map<Long, CategoryAiRow> rows = projectionById();

    // Expense defaults visible, income defaults hidden — both with no source (the type default).
    assertThat(rows.get(groceries).effVisible()).isTrue();
    assertThat(rows.get(groceries).visibleSourceId()).isNull();
    assertThat(rows.get(salary).effVisible()).isFalse();
    assertThat(rows.get(salary).visibleSourceId()).isNull();
  }

  @Test
  void ownFlagOverridesTheTypeDefault() {
    long groceries = insertRoot("Groceries", EXPENSE);
    config(groceries, Boolean.FALSE, null, null);

    CategoryAiRow row = projectionById().get(groceries);
    assertThat(row.ownVisible()).isFalse();
    assertThat(row.effVisible()).isFalse();
    // Set here — the source is the node itself.
    assertThat(row.visibleSourceId()).isEqualTo(groceries);
  }

  @Test
  void childInheritsTheNearestSetAncestor() {
    final long food = insertRoot("Food", EXPENSE);
    final long sweets = insertChild("Sweets", EXPENSE, food);
    final long mms = insertChild("M&Ms", EXPENSE, sweets);
    config(food, Boolean.FALSE, null, null); // hide the whole group at the top

    Map<Long, CategoryAiRow> rows = projectionById();

    // Neither Sweets nor M&Ms carries a flag, so both inherit Food's — the nearest set ancestor.
    assertThat(rows.get(sweets).ownVisible()).isNull();
    assertThat(rows.get(sweets).effVisible()).isFalse();
    assertThat(rows.get(sweets).visibleSourceId()).isEqualTo(food);
    assertThat(rows.get(mms).effVisible()).isFalse();
    assertThat(rows.get(mms).visibleSourceId()).isEqualTo(food);
  }

  @Test
  void leafOverrideWinsUnderHiddenGroupAndKeepsFullPath() {
    long food = insertRoot("Food", EXPENSE);
    long sweets = insertChild("Sweets", EXPENSE, food);
    config(food, Boolean.FALSE, null, null);
    config(sweets, Boolean.TRUE, null, null); // explicit visible under a hidden group

    CategoryAiRow row = projectionById().get(sweets);
    assertThat(row.effVisible()).isTrue();
    assertThat(row.visibleSourceId()).isEqualTo(sweets); // set here beats the group
    assertThat(row.effPath()).isEqualTo("Food - Sweets"); // full path, not just the leaf
  }

  @Test
  void nearestSetAncestorWinsOverFartherOne() {
    long top = insertRoot("Top", EXPENSE);
    long mid = insertChild("Mid", EXPENSE, top);
    long near = insertChild("Near", EXPENSE, mid);
    long leaf = insertChild("Leaf", EXPENSE, near);
    config(top, Boolean.FALSE, null, null);
    config(near, Boolean.TRUE, null, null);

    CategoryAiRow row = projectionById().get(leaf);
    assertThat(row.effVisible()).isTrue();
    assertThat(row.visibleSourceId()).isEqualTo(near); // the nearer flag, not Top's
  }

  @Test
  void groupAliasRenamesChildrensPaths() {
    final long food = insertRoot("Food", EXPENSE);
    final long milk = insertChild("Milk", EXPENSE, food);
    config(food, null, "Groceries", null); // alias only; visibility still inherits

    Map<Long, CategoryAiRow> rows = projectionById();
    assertThat(rows.get(food).effName()).isEqualTo("Groceries");
    assertThat(rows.get(food).effPath()).isEqualTo("Groceries");
    // Milk keeps its own name but its path is renamed by the ancestor alias.
    assertThat(rows.get(milk).effName()).isEqualTo("Milk");
    assertThat(rows.get(milk).effPath()).isEqualTo("Groceries - Milk");
  }

  @Test
  void leafDetectionExcludesCurrencyLeafChildren() {
    final long food = insertRoot("Food", EXPENSE);
    final long milk = insertChild("Milk", EXPENSE, food); // real child → Food is a group
    final long sweets = insertRoot("Sweets", EXPENSE);
    insertCurrencyLeaf("Sweets EUR", EXPENSE, sweets); // only a currency leaf → Sweets stays a leaf

    Map<Long, CategoryAiRow> rows = projectionById();
    assertThat(rows.get(food).leaf()).isFalse();
    assertThat(rows.get(milk).leaf()).isTrue();
    assertThat(rows.get(sweets).leaf()).isTrue();
  }

  @Test
  void currencyLeavesAreNeverProjected() {
    long sweets = insertRoot("Sweets", EXPENSE);
    long eurLeaf = insertCurrencyLeaf("Sweets EUR", EXPENSE, sweets);

    assertThat(projectionById()).containsKey(sweets).doesNotContainKey(eurLeaf);
  }
}
