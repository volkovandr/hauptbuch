package volkovandr.hauptbuch.ledger.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.ExchangeRate;

/**
 * Native-SQL access to the {@code exchange_rate} carry-forward cache (data-model §3.7).
 *
 * <p>The defining query is {@link #rateAsOf}: "the most recent rate on or before D" — sparse rows
 * (monthly ECB + occasional manual) carry forward to fill the gaps. This is the rate used to
 * propose a value on entry and to revalue held balances; it never rewrites a frozen conversion.
 */
@Repository
public class ExchangeRateRepository {

  private static final String CURRENCY_CODE = "currencyCode";
  private static final String DATE = "date";
  private static final String RATE = "rate";
  private static final String SOURCE = "source";

  private final JdbcClient jdbcClient;

  ExchangeRateRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The rate (units of base per 1 unit of {@code currencyCode}) in effect on {@code date}: the most
   * recent stored rate dated on or before it. Empty when no rate has been recorded on or before the
   * date. The caller treats a base-currency lookup as 1 without consulting this.
   */
  public Optional<BigDecimal> rateAsOf(String currencyCode, LocalDate date) {
    return jdbcClient
        .sql(
            """
            select rate
            from exchange_rate
            where currency_code = :currencyCode and date <= :date
            order by date desc
            limit 1
            """)
        .param(CURRENCY_CODE, currencyCode)
        .param(DATE, date)
        .query(BigDecimal.class)
        .optional();
  }

  /** Insert a rate row into the carry-forward cache. */
  public void insert(ExchangeRate rate) {
    jdbcClient
        .sql(
            """
            insert into exchange_rate (currency_code, date, rate, source)
            values (:currencyCode, :date, :rate, :source)
            """)
        .param(CURRENCY_CODE, rate.currencyCode())
        .param(DATE, rate.date())
        .param(RATE, rate.rate())
        .param(SOURCE, rate.source())
        .update();
  }
}
