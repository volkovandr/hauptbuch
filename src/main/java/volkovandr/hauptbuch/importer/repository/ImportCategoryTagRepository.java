package volkovandr.hauptbuch.importer.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to {@code import_category_tag} — the junction carrying a mapped Money path's
 * tags (import.md §5.2, §8; plan d1). A path maps to one Hauptbuch category <em>and</em> zero or
 * more tags; the tag ids are resolved (or created) through {@code categories}' {@code TagService}
 * before they reach here, so this repository only owns the link rows. Plain inserts / deletes /
 * selects — row-mapping round-trips for the integration tier (CLAUDE.md §6). Rows cascade when
 * their {@code import_category} is removed (V19) or, transitively, when the session is discarded.
 */
@Repository
public class ImportCategoryTagRepository {

  private static final String CATEGORY_ID = "importCategoryId";

  private final JdbcClient jdbcClient;

  ImportCategoryTagRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Attach one tag to a map row; a no-op if it is already attached (§8 — a class and the map may
   * contribute the same tag).
   */
  public void addTag(long importCategoryId, long tagId) {
    jdbcClient
        .sql(
            """
            insert into import_category_tag (import_category_id, tag_id)
            values (:importCategoryId, :tagId)
            on conflict (import_category_id, tag_id) do nothing
            """)
        .param(CATEGORY_ID, importCategoryId)
        .param("tagId", tagId)
        .update();
  }

  /**
   * Drop every tag from a map row — the first half of a replace (plan d1's per-row "Save tags").
   */
  public void clearTags(long importCategoryId) {
    jdbcClient
        .sql("delete from import_category_tag where import_category_id = :importCategoryId")
        .param(CATEGORY_ID, importCategoryId)
        .update();
  }

  /** The tag ids attached to one map row, in attachment order. */
  public List<Long> tagIdsFor(long importCategoryId) {
    return jdbcClient
        .sql(
            "select tag_id from import_category_tag where import_category_id = :importCategoryId"
                + " order by import_category_tag_id")
        .param(CATEGORY_ID, importCategoryId)
        .query(Long.class)
        .list();
  }

  /**
   * Every map row's tag ids in a session, keyed by {@code import_category_id} — the category-map
   * panel's read, so it does not fire a query per row. Joins the junction to {@code
   * import_category} for the session scope; assembled in Java (two tables, no grouping —
   * integration tier).
   */
  public Map<Long, List<Long>> tagIdsBySession(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select ict.import_category_id as import_category_id, ict.tag_id as tag_id
              from import_category_tag ict
              join import_category ic on ic.import_category_id = ict.import_category_id
             where ic.import_session_id = :sessionId
             order by ict.import_category_id, ict.import_category_tag_id
            """)
        .param("sessionId", importSessionId)
        .query(Link.class)
        .list()
        .stream()
        .collect(
            Collectors.groupingBy(
                Link::importCategoryId,
                LinkedHashMap::new,
                Collectors.mapping(Link::tagId, Collectors.toList())));
  }

  /** One junction row, for {@link #tagIdsBySession}'s fold into a per-row list. */
  private record Link(long importCategoryId, long tagId) {}
}
