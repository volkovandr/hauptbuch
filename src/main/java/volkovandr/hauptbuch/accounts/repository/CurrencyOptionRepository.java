package volkovandr.hauptbuch.accounts.repository;

import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.shared.CacheNames;

/**
 * Read-only lookup of the seeded currencies for the account form's currency select — see {@link
 * CurrencyOption} for why this is a projection rather than a dependency on {@code ledger}'s {@code
 * Currency} (that dependency would close a module cycle, {@code ledger} → {@code accounts} being
 * the sanctioned direction since stage 6a).
 */
@Repository
public class CurrencyOptionRepository {

  private final JdbcClient jdbcClient;

  CurrencyOptionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** All seeded currencies, ordered by ISO code. */
  @Cacheable(cacheNames = CacheNames.CURRENCY_OPTIONS, condition = CacheNames.OUTSIDE_TRANSACTION)
  public List<CurrencyOption> findAll() {
    return List.copyOf(
        jdbcClient
            .sql("select currency_code as code, name from currency order by currency_code")
            .query(CurrencyOption.class)
            .list());
  }
}
