package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
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
 *
 * <p>Since stage 7b a payee carries an optional city and country (register §3.4); {@link #search}
 * ranks payees against the picker's normalised name+city+country match key.
 */
@Repository
public class PayeeRepository {

  private static final String PAYEE_COLUMNS = "payee_id, name, city, country_code, deleted_at";
  private static final String NAME = "name";
  private static final String CITY = "city";
  private static final String COUNTRY_CODE = "countryCode";
  private static final String PAYEE_ID = "payeeId";
  private static final String NORMALISED = "normalised";
  private static final String LIMIT = "limit";

  private final JdbcClient jdbcClient;

  PayeeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a payee with an optional city and country; returns its generated id. */
  public long insert(String name, String city, String countryCode) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcClient
        .sql(
            "insert into payee (name, city, country_code) " + "values (:name, :city, :countryCode)")
        .param(NAME, name)
        .param(CITY, city)
        .param(COUNTRY_CODE, countryCode)
        .update(keyHolder, "payee_id");
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Insert did not return a generated key");
    }
    return key.longValue();
  }

  /** The live payees, alphabetical — the register's payee filter options (register §2.3). */
  public List<Payee> findAllLive() {
    return jdbcClient
        .sql("select " + PAYEE_COLUMNS + " from payee where deleted_at is null order by name")
        .query(Payee.class)
        .list();
  }

  /**
   * The live payees with a composed {@code Name · City · Country} display label — the register's
   * payee filter options (register §2.3/§3.4), so same-named payees at different addresses are
   * distinguishable in the dropdown. Alphabetical by the label.
   */
  public List<PayeeOption> findFilterOptions() {
    return jdbcClient
        .sql(
            """
            select p.payee_id,
                   p.name
                     || coalesce(' · ' || p.city, '')
                     || coalesce(' · ' || c.name, '') as label,
                   p.name
                     || coalesce(' - ' || p.city, '')
                     || coalesce(' - ' || c.name, '') as entry_value
            from payee p
            left join country c on p.country_code = c.country_code
            where p.deleted_at is null
            order by label
            """)
        .query(PayeeOption.class)
        .list();
  }

  /**
   * A payee option for the register's pickers: its id, a {@code Name · City · Country} display
   * label, and a {@code Name - City - Country} entry value the dock's create-new parser round-trips
   * back to the same payee (so re-picking reuses it, not creates a duplicate — register §3.4).
   *
   * @param payeeId the payee
   * @param label the display label distinguishing same-named payees
   * @param entryValue the parser-round-tripping value for the dock datalist
   */
  public record PayeeOption(long payeeId, String label, String entryValue) {}

  /**
   * The first live payee with exactly this name, city, and country (case-insensitive on name),
   * treating {@code null} city/country as "no value". Used to reuse an existing payee on commit
   * rather than create a duplicate each time (register §3.4) — so re-picking {@code Rewe ·
   * Dortmund} lands the same payee, and the ghost suggestion keeps working.
   */
  public Optional<Payee> findByAddress(String name, String city, String countryCode) {
    return jdbcClient
        .sql(
            "select "
                + PAYEE_COLUMNS
                + " from payee where deleted_at is null"
                + " and lower(name) = lower(:name)"
                + " and city is not distinct from :city"
                + " and country_code is not distinct from :countryCode"
                + " order by payee_id limit 1")
        .param(NAME, name)
        .param(CITY, city)
        .param(COUNTRY_CODE, countryCode)
        .query(Payee.class)
        .optional();
  }

  /** Find a payee by id. */
  public Optional<Payee> findById(long payeeId) {
    return jdbcClient
        .sql("select " + PAYEE_COLUMNS + " from payee where payee_id = :payeeId")
        .param(PAYEE_ID, payeeId)
        .query(Payee.class)
        .optional();
  }

  /**
   * Rank live payees against the picker's match key (register §3.4): the <em>normalised</em>
   * concatenation of name + city + country name — lower-cased, with all non-alphanumerics stripped
   * — matched as a substring of the equally-normalised query. So {@code rewedort} matches {@code
   * Rewe · Dortmund · Germany} (normalised {@code rewedortmundgermany}). A blank query returns the
   * live payees alphabetically. A prefix match ranks before an interior match, then alphabetical.
   *
   * @param query the user's typed text; separators and case are irrelevant (normalised away)
   * @param limit the most matches to return
   */
  public List<Payee> search(String query, int limit) {
    return jdbcClient
        .sql(
            """
            with matchable as (
              select p.payee_id, p.name, p.city, p.country_code, p.deleted_at,
                     regexp_replace(
                       lower(p.name || coalesce(p.city, '') || coalesce(c.name, '')),
                       '[^a-z0-9]', '', 'g'
                     ) as match_key
              from payee p
              left join country c on p.country_code = c.country_code
              where p.deleted_at is null
            )
            select payee_id, name, city, country_code, deleted_at
            from matchable
            where :normalised = '' or match_key like '%' || :normalised || '%'
            order by
              case when match_key like :normalised || '%' then 0 else 1 end,
              name
            limit :limit
            """)
        .param(NORMALISED, normalise(query))
        .param(LIMIT, limit)
        .query(Payee.class)
        .list();
  }

  /**
   * Lower-case and strip every non-alphanumeric, the same shape the SQL builds its match key in.
   */
  private static String normalise(String query) {
    if (query == null) {
      return "";
    }
    return query.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
