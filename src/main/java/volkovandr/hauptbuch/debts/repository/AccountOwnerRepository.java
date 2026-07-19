package volkovandr.hauptbuch.debts.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.debts.AccountOwner;

/**
 * Repository for {@link AccountOwner} junctions. Owns the per-person debt account links and the
 * per-person per-currency signed-balance query.
 */
@Repository
public class AccountOwnerRepository {

  private final JdbcClient jdbcClient;

  AccountOwnerRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert an account_owner link and return the persisted row with its generated ID. */
  public AccountOwner insert(Long accountId, Long personId) {
    return jdbcClient
        .sql("insert into account_owner (account_id, person_id) values (:aid, :pid) returning *")
        .param("aid", accountId)
        .param("pid", personId)
        .query(AccountOwner.class)
        .single();
  }

  /** Fetch the owner link for a given account, if it exists. */
  public Optional<AccountOwner> findByAccountId(Long accountId) {
    return jdbcClient
        .sql("select * from account_owner where account_id = :id")
        .param("id", accountId)
        .query(AccountOwner.class)
        .optional();
  }

  /** Fetch all accounts owned by a person. */
  public List<Long> findAccountIdsByPersonId(Long personId) {
    return jdbcClient
        .sql("select account_id from account_owner where person_id = :pid")
        .param("pid", personId)
        .query(Long.class)
        .list();
  }

  /**
   * Tuple of (personId, currencyCode, signedBalance) from the per-person per-currency balance query
   * (data-model §7). Summed across all live postings to that person's accounts in that currency. A
   * negative balance means the user owes the person; positive means the person owes the user.
   * Scoped to live transactions and postings (`deleted_at is null`).
   */
  public static final class PersonCurrencyBalance {
    private final Long personId;
    private final String currencyCode;
    private final java.math.BigDecimal signedBalance;

    /**
     * Constructor.
     *
     * @param personId the person ID
     * @param currencyCode the currency code
     * @param signedBalance the signed balance
     */
    public PersonCurrencyBalance(
        Long personId, String currencyCode, java.math.BigDecimal signedBalance) {
      this.personId = personId;
      this.currencyCode = currencyCode;
      this.signedBalance = signedBalance;
    }

    /** Get the person ID. */
    public Long getPersonId() {
      return personId;
    }

    /** Get the currency code. */
    public String getCurrencyCode() {
      return currencyCode;
    }

    /** Get the signed balance. */
    public java.math.BigDecimal getSignedBalance() {
      return signedBalance;
    }
  }

  /** Fetch all (personId, currencyCode, signedBalance) tuples for all live persons. */
  public List<PersonCurrencyBalance> findAllPersonCurrencyBalances() {
    return jdbcClient
        .sql(
            """
            select
              p.person_id,
              a.currency_code,
              sum(po.amount) as signed_balance
            from person p
            join account_owner ao on p.person_id = ao.person_id
            join account a on ao.account_id = a.account_id
            join posting po on a.account_id = po.account_id
            join transaction t on po.transaction_id = t.transaction_id
            where p.deleted_at is null and t.deleted_at is null
            group by p.person_id, a.currency_code
            order by p.person_id, a.currency_code
            """)
        .query(
            (rs, rowNum) ->
                new PersonCurrencyBalance(
                    rs.getLong("person_id"),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("signed_balance")))
        .list();
  }

  /**
   * Fetch per-currency signed-balance tuples for a specific person (rows with no postings are
   * filtered out — if a person has no postings in a currency, that (person, currency) pair does not
   * appear).
   */
  public List<PersonCurrencyBalance> findPersonCurrencyBalances(Long personId) {
    return jdbcClient
        .sql(
            """
            select
              a.currency_code,
              sum(po.amount) as signed_balance
            from account_owner ao
            join account a on ao.account_id = a.account_id
            join posting po on a.account_id = po.account_id
            join transaction t on po.transaction_id = t.transaction_id
            where ao.person_id = :pid and t.deleted_at is null
            group by a.currency_code
            order by a.currency_code
            """)
        .param("pid", personId)
        .query(
            (rs, rowNum) ->
                new PersonCurrencyBalance(
                    personId, rs.getString("currency_code"), rs.getBigDecimal("signed_balance")))
        .list();
  }
}
