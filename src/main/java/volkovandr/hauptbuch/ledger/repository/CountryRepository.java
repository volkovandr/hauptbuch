package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Country;

/**
 * Native-SQL read access to the seeded {@code country} reference list (register §3.4, plan stage
 * 7b). Reference data, so plain reads are the right shape (CLAUDE.md §1.7). Two reads:
 *
 * <ul>
 *   <li>{@link #findAll} — the countries the create-new mini-form's country dropdown offers;
 *   <li>{@link #resolveAlias} — the alias lookup the create-new parser uses to tell a country
 *       segment (matched against an alias) from a city segment (matched by nothing).
 * </ul>
 */
@Repository
public class CountryRepository {

  private final JdbcClient jdbcClient;

  CountryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Every seeded country, alphabetical by name — the create-form's country dropdown options. */
  public List<Country> findAll() {
    return jdbcClient
        .sql("select country_code as code, name from country order by name")
        .query(Country.class)
        .list();
  }

  /**
   * Resolve one accepted spelling (canonical name, either ISO code, or a German exonym) to its
   * country code, case-insensitively. The typed segment is lower-cased to match the lower-cased
   * seeded aliases. An empty result means "not a known country" — the parser then treats the
   * segment as a city (register §3.4).
   *
   * @param alias the typed segment, any case
   * @return the ISO-3166 alpha-3 code, or empty if the alias matches no country
   */
  public Optional<String> resolveAlias(String alias) {
    return jdbcClient
        .sql("select country_code from country_alias where alias = :alias")
        .param("alias", alias.trim().toLowerCase(java.util.Locale.ROOT))
        .query(String.class)
        .optional();
  }
}
