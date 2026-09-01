package volkovandr.hauptbuch.importer.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportSession;

/**
 * Native-SQL access to {@code import_session} (import.md §11). Plain single-row inserts, lookups by
 * id or by the open state, and a state flip — row-mapping round-trips for the integration tier
 * (CLAUDE.md §6), not SQL-resident logic.
 *
 * <p>The "one open session at a time" rule is upheld by {@code ImportSessionService} and
 * backstopped by the {@code import_session_single_open_idx} partial unique index; this repository
 * only reads and writes rows.
 */
@Repository
public class ImportSessionRepository {

  private final JdbcClient jdbcClient;

  ImportSessionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Open a fresh campaign — the single {@code open} session every upload then feeds. */
  public ImportSession insertOpen() {
    return jdbcClient
        .sql("insert into import_session (state) values ('open') returning *")
        .query(ImportSession.class)
        .single();
  }

  /** The open session, or empty when no campaign is in progress. */
  public Optional<ImportSession> findOpen() {
    return jdbcClient
        .sql("select * from import_session where state = 'open'")
        .query(ImportSession.class)
        .optional();
  }

  /** A session by id, whatever its state; empty when absent. */
  public Optional<ImportSession> findById(long importSessionId) {
    return jdbcClient
        .sql("select * from import_session where import_session_id = :id")
        .param("id", importSessionId)
        .query(ImportSession.class)
        .optional();
  }

  /**
   * Discard the open session (import.md §2): flips {@code open} → {@code discarded}, leaving its
   * staged rows in place for a later purge. Returns the rows affected — zero when nothing was open.
   */
  public int discardOpen() {
    return jdbcClient
        .sql("update import_session set state = 'discarded' where state = 'open'")
        .update();
  }
}
