package volkovandr.hauptbuch.categories.repository;

import java.util.Collection;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.categories.CategoryAiConfig;

/**
 * Native-SQL CRUD for the {@code category_ai_config} rows (data-model §13.3, plan stage 9d). The AI
 * Vocabulary's config is operator-curated reference data, so plain CRUD-with-reuse is the right
 * shape here (CLAUDE.md §1.7). The one config row per category is keyed by {@code account_id}
 * ({@code unique}), which is what makes a category rename automatic (the row never moves) and a
 * subtree deletion a straight delete-by-ids.
 *
 * <p>The effective-visibility / effective-path <em>projection</em> — the recursive walk over the
 * category hierarchy joined to these rows — lives in {@link AiVocabularyRepository}, not here: its
 * logic is in the SQL (SQL-logic tier), whereas these are plain row round-trips (integration tier).
 */
@Repository
public class CategoryAiConfigRepository {

  private static final String COLUMNS =
      "category_ai_config_id, account_id, visible, alias, ai_note";
  private static final String ACCOUNT_ID = "accountId";
  private static final String VISIBLE = "visible";
  private static final String ALIAS = "alias";
  private static final String AI_NOTE = "aiNote";

  private final JdbcClient jdbcClient;

  CategoryAiConfigRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Insert or update the single config row for a category (the {@code unique(account_id)} makes
   * this an upsert). A {@code null} {@code visible} stores the inherit tri-state; {@code null}
   * alias/note store "no override".
   */
  public void upsert(long accountId, Boolean visible, String alias, String aiNote) {
    jdbcClient
        .sql(
            "insert into category_ai_config (account_id, visible, alias, ai_note)"
                + " values (:accountId, :visible, :alias, :aiNote)"
                + " on conflict (account_id) do update"
                + " set visible = excluded.visible, alias = excluded.alias,"
                + " ai_note = excluded.ai_note")
        .param(ACCOUNT_ID, accountId)
        .param(VISIBLE, visible)
        .param(ALIAS, alias)
        .param(AI_NOTE, aiNote)
        .update();
  }

  /** The config row for one category, if any (its absence means "inherit everything"). */
  public Optional<CategoryAiConfig> findByAccountId(long accountId) {
    return jdbcClient
        .sql("select " + COLUMNS + " from category_ai_config where account_id = :accountId")
        .param(ACCOUNT_ID, accountId)
        .query(CategoryAiConfig.class)
        .optional();
  }

  /** Delete the config row for one category, if present (the editor's "all default" path). */
  public void deleteByAccountId(long accountId) {
    jdbcClient
        .sql("delete from category_ai_config where account_id = :accountId")
        .param(ACCOUNT_ID, accountId)
        .update();
  }

  /**
   * Delete every config row for the given accounts — the subtree-deletion cleanup (data-model
   * §13.3): when a category subtree is deleted, its config rows go with it. An empty collection is
   * a no-op (an empty SQL {@code in ()} is invalid in Postgres).
   */
  public void deleteByAccountIds(Collection<Long> accountIds) {
    if (accountIds.isEmpty()) {
      return;
    }
    jdbcClient
        .sql("delete from category_ai_config where account_id in (:accountIds)")
        .param("accountIds", accountIds)
        .update();
  }
}
