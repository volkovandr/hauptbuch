package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Account;

/**
 * Native-SQL access to the {@code account} table (CLAUDE.md §1.3 — JdbcClient + records, no ORM).
 *
 * <p>Read-only here for stage 3: the engine needs to look accounts up (to learn their currency, to
 * resolve a system leaf, to check leaves-only). Account-definition CRUD is stage 6's {@code
 * accounts} UI.
 */
@Repository
public class AccountRepository {

  private final JdbcClient jdbcClient;

  AccountRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Find an account by id. */
  public Optional<Account> findById(long accountId) {
    return jdbcClient
        .sql(
            """
            select account_id, name, type, parent_id, currency_code,
                   opened_at, closed_at, deleted_at
            from account
            where account_id = :accountId
            """)
        .param("accountId", accountId)
        .query(Account.class)
        .optional();
  }

  /**
   * Resolve a system leaf by its parent's name and the leaf's currency (e.g. the {@code Opening
   * Balances} leaf for EUR, the {@code FX gain/loss} leaf for the base currency). The seed (V2)
   * creates exactly one such leaf per currency under each named system parent.
   */
  public Optional<Account> findLeafUnderParentNamed(String parentName, String currencyCode) {
    return jdbcClient
        .sql(
            """
            select child.account_id, child.name, child.type, child.parent_id,
                   child.currency_code, child.opened_at, child.closed_at, child.deleted_at
            from account child
            join account parent on child.parent_id = parent.account_id
            where parent.name = :parentName
              and parent.parent_id is null
              and child.currency_code = :currencyCode
            """)
        .param("parentName", parentName)
        .param("currencyCode", currencyCode)
        .query(Account.class)
        .optional();
  }

  /** The account ids that are some other account's parent — i.e. the non-leaf accounts. */
  public List<Long> findParentAccountIds() {
    return jdbcClient
        .sql("select distinct parent_id from account where parent_id is not null")
        .query(Long.class)
        .list();
  }
}
