package volkovandr.hauptbuch.operations.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL support for the {@code subdivideAccount} domain operation (plan stage 6b, data-model
 * §5): moving a leaf's existing postings onto a newly-created catch-all sibling.
 *
 * <p>This is a direct, structural update of {@code posting.account_id} — not a re-post through
 * {@code LedgerService}. Subdivision changes <em>where</em> a historical leg is filed, never its
 * amount, currency, or the transaction it belongs to, so none of the engine's balance/invariant
 * validation applies; going through the engine would be the wrong tool; a deliberate, narrow SQL
 * peek/write is the same stance {@code accounts}' {@code AccountRepository.hasPostings} already
 * takes on the {@code posting} table.
 */
@Repository
public class PostingReassignmentRepository {

  private final JdbcClient jdbcClient;

  PostingReassignmentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Repoint every posting on {@code fromAccountId} to {@code toAccountId}. */
  public void reassignPostings(long fromAccountId, long toAccountId) {
    jdbcClient
        .sql("update posting set account_id = :toAccountId where account_id = :fromAccountId")
        .param("toAccountId", toAccountId)
        .param("fromAccountId", fromAccountId)
        .update();
  }
}
