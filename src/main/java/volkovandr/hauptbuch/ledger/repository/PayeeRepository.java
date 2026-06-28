package volkovandr.hauptbuch.ledger.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Payee;

/**
 * Native-SQL CRUD for {@code payee} (data-model §3.4). A payee is true reference data, so plain
 * CRUD is the right shape here (CLAUDE.md §1.7) — unlike transactions, which are
 * invariant-upholding domain operations.
 */
@Repository
public class PayeeRepository {

  private final JdbcClient jdbcClient;

  PayeeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a payee and return its generated id. */
  public long insert(String name) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcClient
        .sql("insert into payee (name) values (:name)")
        .param("name", name)
        .update(keyHolder, "payee_id");
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Insert did not return a generated key");
    }
    return key.longValue();
  }

  /** Find a payee by id. */
  public Optional<Payee> findById(long payeeId) {
    return jdbcClient
        .sql("select payee_id, name, deleted_at from payee where payee_id = :payeeId")
        .param("payeeId", payeeId)
        .query(Payee.class)
        .optional();
  }
}
