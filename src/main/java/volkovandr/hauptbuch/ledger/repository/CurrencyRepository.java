package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Currency;

/**
 * Native-SQL access to the seeded {@code currency} table (data-model §3.1 — JdbcClient + records,
 * no ORM). Read-only reference data; the seed (V2) is the sole writer.
 *
 * <p>Its first consumer is the settings UI, which offers the seeded currencies as base-currency
 * choices on first run (plan stage 5).
 */
@Repository
public class CurrencyRepository {

  private final JdbcClient jdbcClient;

  CurrencyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** All seeded currencies, ordered by ISO code for a stable dropdown. */
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
}
