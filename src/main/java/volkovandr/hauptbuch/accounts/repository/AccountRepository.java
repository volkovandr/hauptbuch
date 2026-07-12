package volkovandr.hauptbuch.accounts.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;

/**
 * Native-SQL access to the {@code account} table (CLAUDE.md §1.3 — JdbcClient + records, no ORM).
 * Moved here from {@code ledger} at stage 6a, gaining the writes: this module owns the account
 * table now; the engine reads accounts through {@link
 * volkovandr.hauptbuch.accounts.AccountService}.
 *
 * <p>{@link #hasPostings} reads the {@code posting} table — a deliberate, read-only, SQL-level
 * peek: the leaves-only invariant (data-model §5) must be enforced when a parent is chosen, and
 * asking the engine would invert the module dependency (see {@code OpeningBalanceRecorder}).
 */
@Repository
public class AccountRepository {

  private static final String ACCOUNT_ID = "accountId";
  private static final String PARENT_NAME = "parentName";
  private static final String CURRENCY_CODE = "currencyCode";
  private static final String PARENT_ID = "parentId";
  private static final String NEW_PARENT_ID = "newParentId";
  private static final String NAME = "name";
  private static final String TYPES = "types";
  private static final String ROOT_ID = "rootId";
  private static final String ACCOUNT_IDS = "accountIds";
  private static final String OPENED_AT = "openedAt";
  private static final String CLOSED_AT = "closedAt";
  private static final String HUE = "hue";
  private static final String TYPE = "type";
  private static final String CURRENCY_LEAF = "currencyLeaf";

  /** The {@code select} clause every {@link Account}-mapping query starts with. */
  private static final String SELECT_ACCOUNT_COLUMNS =
      """
      select account_id, name, type, parent_id, currency_code, hue,
             opened_at, closed_at, deleted_at, currency_leaf
      """;

  private final JdbcClient jdbcClient;

  AccountRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Find an account by id. */
  public Optional<Account> findById(long accountId) {
    return jdbcClient
        .sql(SELECT_ACCOUNT_COLUMNS + "from account where account_id = :accountId")
        .param(ACCOUNT_ID, accountId)
        .query(Account.class)
        .optional();
  }

  /**
   * Resolve a system leaf by its parent's name and the leaf's currency (e.g. the {@code Opening
   * Balances} leaf for EUR). The seed (V2) creates exactly one such leaf per currency under the
   * named system parent.
   */
  public Optional<Account> findLeafUnderParentNamed(String parentName, String currencyCode) {
    return jdbcClient
        .sql(
            """
            select child.account_id, child.name, child.type, child.parent_id,
                   child.currency_code, child.hue, child.opened_at, child.closed_at,
                   child.deleted_at, child.currency_leaf
            from account child
            join account parent on child.parent_id = parent.account_id
            where parent.name = :parentName
              and parent.parent_id is null
              and child.currency_code = :currencyCode
            """)
        .param(PARENT_NAME, parentName)
        .param(CURRENCY_CODE, currencyCode)
        .query(Account.class)
        .optional();
  }

  /**
   * Resolve a top-level (parentless) account by name — e.g. the {@code Opening Balances} system
   * parent the seed (V2) creates once, under which each currency has one leaf. Used by the {@code
   * createCurrency} operation to hang the new currency's system leaf.
   */
  public Optional<Account> findTopLevelByName(String name) {
    return jdbcClient
        .sql(
            SELECT_ACCOUNT_COLUMNS
                + """
                from account
                where name = :name
                  and parent_id is null
                  and deleted_at is null
                """)
        .param(NAME, name)
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

  /** The live children of an account, alphabetical by name. */
  public List<Account> findChildrenOf(long parentId) {
    return jdbcClient
        .sql(
            SELECT_ACCOUNT_COLUMNS
                + """
                from account
                where parent_id = :parentId
                  and deleted_at is null
                order by name
                """)
        .param(PARENT_ID, parentId)
        .query(Account.class)
        .list();
  }

  /**
   * The live (not soft-deleted) accounts of the given types, parents before their children and
   * alphabetical within a level — the accounts screen's list order.
   */
  public List<Account> findLiveByTypes(List<String> types) {
    return jdbcClient
        .sql(
            SELECT_ACCOUNT_COLUMNS
                + """
                from account
                where type in (:types)
                  and deleted_at is null
                order by type, coalesce(parent_id, account_id), parent_id is not null, name
                """)
        .param(TYPES, types)
        .query(Account.class)
        .list();
  }

  /**
   * The live accounts of the given types, each annotated with its true depth in the parent-chain (0
   * = top level, 1 = child, 2 = grandchild, …) and listed depth-first — every node immediately
   * followed by all of its descendants, alphabetical among siblings at each level. A recursive CTE
   * walks {@code parent_id} to arbitrary depth (data-model §5's hierarchy is not limited to two
   * levels; a flat "has a parent or not" check under-counts grandchildren and deeper).
   */
  public List<AccountNode> findLiveByTypesWithDepth(List<String> types) {
    return jdbcClient
        .sql(
            """
            with recursive tree as (
              select account_id, name, type, parent_id, currency_code, hue,
                     opened_at, closed_at, deleted_at, currency_leaf,
                     0 as depth,
                     array[name] as sort_path
              from account
              where type in (:types)
                and deleted_at is null
                and parent_id is null
              union all
              select a.account_id, a.name, a.type, a.parent_id, a.currency_code, a.hue,
                     a.opened_at, a.closed_at, a.deleted_at, a.currency_leaf,
                     tree.depth + 1,
                     tree.sort_path || a.name
              from account a
              join tree on a.parent_id = tree.account_id
              where a.deleted_at is null
            )
            select account_id, name, type, parent_id, currency_code, hue,
                   opened_at, closed_at, deleted_at, currency_leaf, depth
            from tree
            order by type, sort_path
            """)
        .param(TYPES, types)
        .query(
            (rs, rowNum) ->
                new AccountNode(
                    new Account(
                        rs.getLong("account_id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getObject("parent_id", Long.class),
                        rs.getString("currency_code"),
                        rs.getObject("hue", Integer.class),
                        rs.getObject("opened_at", LocalDate.class),
                        rs.getObject("closed_at", LocalDate.class),
                        rs.getObject("deleted_at", OffsetDateTime.class),
                        rs.getBoolean("currency_leaf")),
                    rs.getInt("depth")))
        .list();
  }

  /**
   * The account ids of a subtree: the given root and every live descendant, walked to arbitrary
   * depth via {@code parent_id} (data-model §5's hierarchy is not limited to two levels). Used by
   * the category-deletion operation, which removes a whole subtree at once (plan stage 6c).
   */
  public List<Long> findSubtreeAccountIds(long rootId) {
    return jdbcClient
        .sql(
            """
            with recursive subtree as (
              select account_id
              from account
              where account_id = :rootId
                and deleted_at is null
              union all
              select a.account_id
              from account a
              join subtree on a.parent_id = subtree.account_id
              where a.deleted_at is null
            )
            select account_id from subtree
            """)
        .param(ROOT_ID, rootId)
        .query(Long.class)
        .list();
  }

  /**
   * Soft-delete a set of accounts by stamping {@code deleted_at} — the account-side of category
   * deletion (plan stage 6c). Unlike {@link #close}, this is not a display state the UI can undo;
   * the category screen offers no reopen for a deleted category. Postings are reassigned away first
   * by the caller, so a deleted account carries none.
   *
   * @return the number of rows stamped
   */
  public int softDelete(List<Long> accountIds) {
    if (accountIds.isEmpty()) {
      return 0;
    }
    return jdbcClient
        .sql(
            """
            update account
            set deleted_at = now()
            where account_id in (:accountIds)
              and deleted_at is null
            """)
        .param(ACCOUNT_IDS, accountIds)
        .update();
  }

  /** The account ids at least one posting — live or voided — has ever hit. */
  public List<Long> findPostedAccountIds() {
    return jdbcClient.sql("select distinct account_id from posting").query(Long.class).list();
  }

  /** Whether any posting — live or voided — has ever hit this account (leaves-only guard). */
  public boolean hasPostings(long accountId) {
    return jdbcClient
        .sql("select exists(select 1 from posting where account_id = :accountId)")
        .param(ACCOUNT_ID, accountId)
        .query(Boolean.class)
        .single();
  }

  /** Insert a new account and return its generated id. */
  public long insert(Account account) {
    return jdbcClient
        .sql(
            """
            insert into account
              (name, type, parent_id, currency_code, hue, opened_at, currency_leaf)
            values
              (:name, :type, :parentId, :currencyCode, :hue, :openedAt, :currencyLeaf)
            returning account_id
            """)
        .param(NAME, account.name())
        .param(TYPE, account.type())
        .param(PARENT_ID, account.parentId())
        .param(CURRENCY_CODE, account.currencyCode())
        .param(HUE, account.hue())
        .param(OPENED_AT, account.openedAt())
        .param(CURRENCY_LEAF, account.currencyLeaf())
        .query(Long.class)
        .single();
  }

  /** Update the freely-editable fields: display name and stored hue. */
  public int updateNameAndHue(long accountId, String name, Integer hue) {
    return jdbcClient
        .sql("update account set name = :name, hue = :hue where account_id = :accountId")
        .param(NAME, name)
        .param(HUE, hue)
        .param(ACCOUNT_ID, accountId)
        .update();
  }

  /**
   * Re-parent an account — used only by the currency-leaf-aware subdivision operation to move a
   * category's existing per-currency leaves under its new catch-all sibling (data-model §6.5); not
   * a user-facing edit (re-parenting a posted-to account is otherwise forbidden, data-model §5).
   */
  public int updateParent(long accountId, long newParentId) {
    return jdbcClient
        .sql("update account set parent_id = :newParentId where account_id = :accountId")
        .param(NEW_PARENT_ID, newParentId)
        .param(ACCOUNT_ID, accountId)
        .update();
  }

  /** Stamp {@code closed_at} on a live, still-open account; affects no rows otherwise. */
  public int close(long accountId, LocalDate closedAt) {
    return jdbcClient
        .sql(
            """
            update account
            set closed_at = :closedAt
            where account_id = :accountId
              and closed_at is null
              and deleted_at is null
            """)
        .param(CLOSED_AT, closedAt)
        .param(ACCOUNT_ID, accountId)
        .update();
  }

  /** Clear {@code closed_at} on a live, closed account; affects no rows otherwise. */
  public int reopen(long accountId) {
    return jdbcClient
        .sql(
            """
            update account
            set closed_at = null
            where account_id = :accountId
              and closed_at is not null
              and deleted_at is null
            """)
        .param(ACCOUNT_ID, accountId)
        .update();
  }
}
