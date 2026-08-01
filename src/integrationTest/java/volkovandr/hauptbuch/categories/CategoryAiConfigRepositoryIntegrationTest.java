package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.categories.repository.CategoryAiConfigRepository;

/**
 * Integration tier (plan §1.5): {@link CategoryAiConfigRepository} maps {@code category_ai_config}
 * rows ↔ {@link CategoryAiConfig} records against real Postgres — the upsert (insert then in-place
 * update via {@code unique(account_id)}), the single-row read, and the delete-by-id / delete-by-ids
 * cleanup. Plain round-trips; the effective-visibility projection is SQL-logic-tested separately.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CategoryAiConfigRepositoryIntegrationTest {

  @Autowired CategoryAiConfigRepository repository;
  @Autowired JdbcClient jdbcClient;

  private long insertCategory(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  @Test
  void insertsAndReadsBackConfigRow() {
    long foodId = insertCategory("Food", "expense");

    repository.upsert(foodId, Boolean.FALSE, "Groceries", "M&Ms → for Bobby");

    CategoryAiConfig loaded = repository.findByAccountId(foodId).orElseThrow();
    assertThat(loaded.accountId()).isEqualTo(foodId);
    assertThat(loaded.visible()).isFalse();
    assertThat(loaded.alias()).isEqualTo("Groceries");
    assertThat(loaded.aiNote()).isEqualTo("M&Ms → for Bobby");
  }

  @Test
  void upsertReplacesTheExistingRowRatherThanForkingIt() {
    long foodId = insertCategory("Food", "expense");
    repository.upsert(foodId, Boolean.TRUE, "First", "note one");

    repository.upsert(foodId, null, null, "note two");

    CategoryAiConfig loaded = repository.findByAccountId(foodId).orElseThrow();
    assertThat(loaded.visible()).isNull();
    assertThat(loaded.alias()).isNull();
    assertThat(loaded.aiNote()).isEqualTo("note two");
    // Still exactly one row for the account (the upsert updated in place).
    Long count =
        jdbcClient
            .sql("select count(*) from category_ai_config where account_id = :id")
            .param("id", foodId)
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void storesTheInheritTriStateAsNullVisible() {
    long foodId = insertCategory("Food", "expense");

    repository.upsert(foodId, null, null, null);

    assertThat(repository.findByAccountId(foodId).orElseThrow().visible()).isNull();
  }

  @Test
  void findsNothingForCategoryWithNoConfig() {
    long foodId = insertCategory("Food", "expense");
    assertThat(repository.findByAccountId(foodId)).isEmpty();
  }

  @Test
  void deletesTheConfigRowForOneCategory() {
    long foodId = insertCategory("Food", "expense");
    repository.upsert(foodId, Boolean.FALSE, null, null);

    repository.deleteByAccountId(foodId);

    assertThat(repository.findByAccountId(foodId)).isEmpty();
  }

  @Test
  void deletesConfigRowsForWholeSubtree() {
    long foodId = insertCategory("Food", "expense");
    long sweetsId = insertCategory("Sweets", "expense");
    long keptId = insertCategory("Fuel", "expense");
    repository.upsert(foodId, Boolean.FALSE, null, null);
    repository.upsert(sweetsId, Boolean.TRUE, null, null);
    repository.upsert(keptId, Boolean.FALSE, null, null);

    repository.deleteByAccountIds(List.of(foodId, sweetsId));

    assertThat(repository.findByAccountId(foodId)).isEmpty();
    assertThat(repository.findByAccountId(sweetsId)).isEmpty();
    assertThat(repository.findByAccountId(keptId)).isPresent();
  }

  @Test
  void deleteByAccountIdsIsNoOpForAnEmptyCollection() {
    long foodId = insertCategory("Food", "expense");
    repository.upsert(foodId, Boolean.FALSE, null, null);

    repository.deleteByAccountIds(List.of());

    assertThat(repository.findByAccountId(foodId)).isPresent();
  }
}
