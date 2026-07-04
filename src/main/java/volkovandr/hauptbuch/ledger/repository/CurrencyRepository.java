package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Currency;

/**
 * Native-SQL access to the {@code currency} table (data-model §3.1 — JdbcClient + records, no ORM).
 * The V2 seed lays down the common currencies; since stage 6d the {@code createCurrency} operation
 * is a second writer, adding a currency the user needs that was not seeded.
 *
 * <p>Its first consumer is the settings UI, which offers the currencies as base-currency choices on
 * first run (plan stage 5).
 */
@Repository
public class CurrencyRepository {

  private final JdbcClient jdbcClient;

  CurrencyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** All currencies, ordered by ISO code for a stable dropdown. */
  public List<Currency> findAll() {
    return jdbcClient
        .sql(
            """
            select currency_code as code, minor_units, symbol, name
            from currency
            order by currency_code
            """)
        .query(Currency.class)
        .list();
  }

  /** Whether a currency with this ISO code already exists (its natural key). */
  public boolean existsByCode(String code) {
    return jdbcClient
        .sql("select exists(select 1 from currency where currency_code = :code)")
        .param("code", code)
        .query(Boolean.class)
        .single();
  }

  /**
   * Insert a currency. The {@code createCurrency} operation (plan stage 6d) is now a second writer
   * alongside the V2 seed — a user may add a currency that was not among the seeded seven.
   */
  public void insert(Currency currency) {
    jdbcClient
        .sql(
            """
            insert into currency (currency_code, minor_units, symbol, name)
            values (:code, :minorUnits, :symbol, :name)
            """)
        .param("code", currency.code())
        .param("minorUnits", currency.minorUnits())
        .param("symbol", currency.symbol())
        .param("name", currency.name())
        .update();
  }
}
