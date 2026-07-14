package volkovandr.hauptbuch.categories.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.categories.Tag;

/**
 * Native-SQL CRUD for the {@code tag} vocabulary (data-model §10.1). A tag is shared-taxonomy
 * reference data, so plain CRUD-with-reuse is the right shape here (CLAUDE.md §1.7) — unlike
 * transactions, which are invariant-upholding domain operations. The {@code posting_tag} linkage
 * (attaching a tag to a leg) lives with the posting in {@code ledger}; this repository owns only
 * the tag entity itself.
 *
 * <p>Reuse (rather than a hard {@code unique(name, parent_id)}) is decided in {@link
 * volkovandr.hauptbuch.categories.TagService} via {@link #findByNameAndParent}, mirroring {@code
 * payee}: a retyped {@code Audi} resolves to the same row, but the door stays open to a future
 * merge of genuine duplicates.
 */
@Repository
public class TagRepository {

  private static final String TAG_COLUMNS = "tag_id, name, parent_id, deleted_at";
  private static final String NAME = "name";
  private static final String PARENT_ID = "parentId";
  private static final String TAG_ID = "tagId";

  private final JdbcClient jdbcClient;

  TagRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a tag under an optional parent; returns its generated id. */
  public long insert(String name, Long parentId) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcClient
        .sql("insert into tag (name, parent_id) values (:name, :parentId)")
        .param(NAME, name)
        .param(PARENT_ID, parentId)
        .update(keyHolder, "tag_id");
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Insert did not return a generated key");
    }
    return key.longValue();
  }

  /** Find a tag by id (live or soft-deleted). */
  public Optional<Tag> findById(long tagId) {
    return jdbcClient
        .sql("select " + TAG_COLUMNS + " from tag where tag_id = :tagId")
        .param(TAG_ID, tagId)
        .query(Tag.class)
        .optional();
  }

  /**
   * The first live tag with exactly this name (case-insensitive) under the given parent, treating a
   * {@code null} parent as "top level". The reuse lookup {@link
   * volkovandr.hauptbuch.categories.TagService#resolveChips} keys on, so re-typing {@code
   * Car:Passat} lands the same {@code Passat} under the same {@code Car} rather than forking a
   * duplicate (data-model §10.1).
   */
  public Optional<Tag> findByNameAndParent(String name, Long parentId) {
    return jdbcClient
        .sql(
            "select "
                + TAG_COLUMNS
                + " from tag where deleted_at is null"
                + " and lower(name) = lower(:name)"
                + " and parent_id is not distinct from :parentId"
                + " order by tag_id limit 1")
        .param(NAME, name)
        .param(PARENT_ID, parentId)
        .query(Tag.class)
        .optional();
  }
}
