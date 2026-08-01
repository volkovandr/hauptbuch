package volkovandr.hauptbuch.categories.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The AI-Vocabulary projection query (data-model §13.3, plan stage 9d): a single recursive CTE that
 * walks the live income/expense category hierarchy, joins each node to its optional {@code
 * category_ai_config} row, and carries down the two facts the walk must resolve — <em>effective
 * visibility</em> (own tri-state flag, else the nearest ancestor with a set flag, else the type
 * default) and the <em>effective path</em> (each ancestor's alias-or-real name, so a group alias
 * renames its children's paths). This is SQL-resident logic (a {@code with recursive} carry-down),
 * so it lives here and is exercised in the SQL-logic tier — distinct from {@link
 * CategoryAiConfigRepository}'s plain CRUD round-trips.
 *
 * <p>The query returns <em>every</em> live category node (currency leaves excluded — they are never
 * part of the semantic vocabulary, §6.5), visible or not; the service does the light Java assembly
 * (prune hidden, build the tree, match names) over these rows, exactly as {@code
 * AccountRepository.findPostableLeafPaths} composes paths in Java over its own walk.
 */
@Repository
public class AiVocabularyRepository {

  private final JdbcClient jdbcClient;

  AiVocabularyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The projected category nodes for the given types (always {@code income} + {@code expense}),
   * each carrying its resolved effective visibility, source, effective name and full effective path
   * (ancestor effective names joined by {@code separator}, e.g. {@code Food - Sweets}).
   *
   * <p>Effective visibility is carried down the tree: a node takes its own flag when set, else the
   * value inherited from its parent (which already folds in the nearest set ancestor or the type
   * default). {@code visible_source_id} tracks <em>which</em> ancestor's flag won, so the editor
   * can say "via parent 'Food'"; it is {@code null} when no ancestor sets one and the type default
   * (a node is visible iff it is an expense) applies.
   */
  public List<CategoryAiRow> projection(List<String> types, String separator) {
    return jdbcClient
        .sql(
            """
            with recursive tree as (
              select a.account_id, a.name, a.type, a.parent_id,
                     c.visible as own_visible,
                     coalesce(c.visible, a.type = 'expense') as eff_visible,
                     case when c.visible is not null then a.account_id end as visible_source_id,
                     nullif(c.alias, '') as alias,
                     nullif(c.ai_note, '') as ai_note,
                     coalesce(nullif(c.alias, ''), a.name) as eff_name,
                     array[coalesce(nullif(c.alias, ''), a.name)] as eff_path
              from account a
              left join category_ai_config c on c.account_id = a.account_id
              where a.type in (:types)
                and a.deleted_at is null
                and a.currency_leaf = false
                and a.parent_id is null
              union all
              select a.account_id, a.name, a.type, a.parent_id,
                     c.visible as own_visible,
                     coalesce(c.visible, tree.eff_visible) as eff_visible,
                     coalesce(
                       case when c.visible is not null then a.account_id end,
                       tree.visible_source_id) as visible_source_id,
                     nullif(c.alias, '') as alias,
                     nullif(c.ai_note, '') as ai_note,
                     coalesce(nullif(c.alias, ''), a.name) as eff_name,
                     tree.eff_path || coalesce(nullif(c.alias, ''), a.name)
              from account a
              join tree on a.parent_id = tree.account_id
              left join category_ai_config c on c.account_id = a.account_id
              where a.deleted_at is null
                and a.currency_leaf = false
            )
            select account_id, name, type, parent_id,
                   not exists (
                     select 1 from account ch
                     where ch.parent_id = tree.account_id
                       and ch.deleted_at is null
                       and ch.currency_leaf = false
                       and ch.type in (:types)
                   ) as leaf,
                   own_visible, eff_visible, visible_source_id, alias, ai_note, eff_name,
                   array_to_string(eff_path, :separator) as eff_path
            from tree
            order by type, eff_path
            """)
        .param("types", types)
        .param("separator", separator)
        .query(
            (rs, rowNum) ->
                new CategoryAiRow(
                    rs.getLong("account_id"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getObject("parent_id", Long.class),
                    rs.getBoolean("leaf"),
                    rs.getObject("own_visible", Boolean.class),
                    rs.getBoolean("eff_visible"),
                    rs.getObject("visible_source_id", Long.class),
                    rs.getString("alias"),
                    rs.getString("ai_note"),
                    rs.getString("eff_name"),
                    rs.getString("eff_path")))
        .list();
  }
}
