package volkovandr.hauptbuch.ledger.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * Native-SQL reads of the tags carried by postings (register §3.6, plan stage 7e) — the register's
 * Category-cell chips, the dock's edit-mode pre-fill, and the tag datalist's suggestions.
 *
 * <p>The {@code tag} vocabulary is owned by {@code categories}, but SQL tables are shared: {@code
 * ledger} owns the {@code posting_tag} linkage on its own postings and reads the {@code tag} table
 * directly to compose each tag's canonical {@code Parent:Child} label. No cross-module Java
 * dependency is created (CLAUDE.md §1.1) — {@code categories} composes the same label from Java for
 * its resolve endpoint; each module reads the shared table itself, which is the boundary's cost.
 *
 * <p>The label is a root-to-node path built by a recursive CTE over {@code tag.parent_id}
 * (arbitrary depth, data-model §10.3) — SQL-resident logic, exercised in the {@code sqlLogicTest}
 * tier.
 */
@Repository
public class TagReadRepository {

  /**
   * Every tag's full {@code Parent:Child} label, composed root-to-leaf over the hierarchy. All tags
   * are included (soft-deleted ones too) so a posting that references a since-deleted tag still
   * renders its label; callers that must not <em>offer</em> a deleted tag filter on {@code
   * deleted_at} themselves.
   */
  private static final String LABELLED =
      """
      with recursive labelled as (
        select tag_id, name::text as label, parent_id, deleted_at
        from tag
        where parent_id is null
        union all
        select t.tag_id, l.label || ':' || t.name, t.parent_id, t.deleted_at
        from tag t
        join labelled l on t.parent_id = l.tag_id
      )
      """;

  private final JdbcClient jdbcClient;

  TagReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The distinct tags carried by a transaction's postings, label-ascending — the dock's edit-mode
   * pre-fill (register §3.1/§3.6). Since a transaction-level tag lands on every leg (data-model
   * §10.2), {@code distinct} collapses the per-leg copies back to the set the user entered.
   */
  public List<TransactionTag> tagsForTransaction(long transactionId) {
    return jdbcClient
        .sql(
            LABELLED
                + """
                select distinct lb.tag_id, lb.label
                from labelled lb
                join posting_tag pt on pt.tag_id = lb.tag_id
                join posting p on p.posting_id = pt.posting_id
                where p.transaction_id = :transactionId
                order by lb.label
                """)
        .param("transactionId", transactionId)
        .query(TransactionTag.class)
        .list();
  }

  /**
   * The tags carried by each of the given postings, keyed by posting id — the register's
   * Category-cell chips (register §2.6/§3.6). A posting with no tags is absent from the map. Labels
   * are ascending within each posting.
   */
  public Map<Long, List<String>> labelsByPosting(List<Long> postingIds) {
    if (postingIds.isEmpty()) {
      return Map.of();
    }
    record PostingLabel(long postingId, String label) {}

    return jdbcClient
        .sql(
            LABELLED
                + """
                select pt.posting_id, lb.label
                from labelled lb
                join posting_tag pt on pt.tag_id = lb.tag_id
                where pt.posting_id in (:postingIds)
                order by pt.posting_id, lb.label
                """)
        .param("postingIds", postingIds)
        .query(PostingLabel.class)
        .list()
        .stream()
        .collect(
            Collectors.groupingBy(
                PostingLabel::postingId,
                Collectors.mapping(PostingLabel::label, Collectors.toList())));
  }

  /**
   * The canonical label of each of the given tag ids, keyed by id — the split panel's chip pills
   * mid-entry (register §3.6, plan stage 7e.3), where the form carries only the resolved ids and
   * the panel re-renders the pills on each round-trip (add/remove line, currency change). Ids not
   * found are simply absent from the map. Soft-deleted tags are included (like {@link
   * #tagsForTransaction}) so an already-attached-but-since-deleted tag still renders its pill.
   */
  public Map<Long, String> labelsForTagIds(Collection<Long> tagIds) {
    if (tagIds.isEmpty()) {
      return Map.of();
    }
    record IdLabel(long tagId, String label) {}

    return jdbcClient
        .sql(LABELLED + "select tag_id, label from labelled where tag_id in (:tagIds)")
        .param("tagIds", tagIds)
        .query(IdLabel.class)
        .list()
        .stream()
        .collect(Collectors.toMap(IdLabel::tagId, IdLabel::label));
  }

  /**
   * Every live tag's canonical label, ascending — the dock's tag datalist suggestions (register
   * §3.6). Soft-deleted tags are not offered (but a tag whose ancestor is deleted still resolves,
   * since the label CTE spans all rows).
   */
  public List<String> liveTagLabels() {
    return jdbcClient
        .sql(LABELLED + "select label from labelled where deleted_at is null order by label")
        .query(String.class)
        .list();
  }
}
