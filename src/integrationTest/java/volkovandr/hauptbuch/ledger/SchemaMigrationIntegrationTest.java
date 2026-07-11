package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): asserts the Flyway migrations apply cleanly on a fresh container
 * and the resulting schema and seed data are what the engine expects. A "tested migration" (NFR-06)
 * is one that applies on a fresh container and whose result is asserted — this is that test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationIntegrationTest {

  private static final String ALL_CURRENCIES =
      "select currency_code from currency order by currency_code";

  @Autowired JdbcClient jdbcClient;

  private List<String> leafCurrenciesUnder(String parentName) {
    return jdbcClient
        .sql(
            """
            select child.currency_code
            from account child
            join account parent on child.parent_id = parent.account_id
            where parent.name = :parentName and parent.parent_id is null
            order by child.currency_code
            """)
        .param("parentName", parentName)
        .query(String.class)
        .list();
  }

  private String leafTypeUnder(String parentName) {
    return jdbcClient
        .sql(
            """
            select distinct child.type
            from account child
            join account parent on child.parent_id = parent.account_id
            where parent.name = :parentName
            """)
        .param("parentName", parentName)
        .query(String.class)
        .single();
  }

  @Test
  void settingsStartsAsSingleRowWithNoBaseCurrency() {
    Long rowCount = jdbcClient.sql("select count(*) from settings").query(Long.class).single();
    assertThat(rowCount).isEqualTo(1L);

    assertThat(
            jdbcClient
                .sql("select base_currency from settings where settings_id = 1")
                .query(String.class)
                .optional())
        .isEmpty();
  }

  @Test
  void settingsRowGuardRejectsSecondRow() {
    // The settings_id = 1 check plus the single seeded row make this strictly single-row.
    assertThatThrownBy(
        () -> jdbcClient.sql("insert into settings (settings_id) values (2)").update());
  }

  @Test
  void seedsTheCurrenciesActuallyUsed() {
    List<String> codes = jdbcClient.sql(ALL_CURRENCIES).query(String.class).list();
    // The currencies the owner actually transacts in (data-model §3.1 — seed only those in use).
    assertThat(codes).contains("EUR", "CHF", "USD", "GBP", "JPY");
  }

  @Test
  void japaneseYenHasZeroMinorUnits() {
    Integer minorUnits =
        jdbcClient
            .sql("select minor_units from currency where currency_code = 'JPY'")
            .query(Integer.class)
            .single();
    assertThat(minorUnits).isZero();
  }

  @Test
  void seedsAnOpeningBalancesEquityLeafPerCurrency() {
    // One leaf per seeded currency — the seed builds the leaf set from the currency table.
    List<String> allCurrencies = jdbcClient.sql(ALL_CURRENCIES).query(String.class).list();
    assertThat(leafCurrenciesUnder("Opening Balances"))
        .containsExactlyInAnyOrderElementsOf(allCurrencies);
    assertThat(leafTypeUnder("Opening Balances")).isEqualTo("equity");
  }

  @Test
  void accountCarriesNullableStoredHueWithinTheColourWheel() {
    // V3: the stored two-tone hue (register §2.8). Nullable — the seeded system accounts have none.
    Long seededWithHue =
        jdbcClient
            .sql("select count(*) from account where hue is not null")
            .query(Long.class)
            .single();
    assertThat(seededWithHue).isZero();

    // The check constraint keeps a hue on the HSL colour wheel.
    assertThatThrownBy(
        () ->
            jdbcClient
                .sql(
                    "insert into account (name, type, currency_code, hue) "
                        + "values ('Bad hue', 'asset', 'EUR', 360)")
                .update());
  }

  @Test
  void seedsCountriesWithIsoAlpha3NaturalKeys() {
    // V4: the country reference list for the payee create-new parser (register §3.4). Natural key
    // is
    // the ISO-3166 alpha-3 code, like currency's ISO-4217 code (data-model §3.0).
    List<String> codes =
        jdbcClient.sql("select country_code from country").query(String.class).list();
    assertThat(codes).contains("DEU", "CHE", "FRA", "GBR", "USA");
    assertThat(
            jdbcClient
                .sql("select name from country where country_code = 'DEU'")
                .query(String.class)
                .single())
        .isEqualTo("Germany");
  }

  @Test
  void countryAliasesResolveCanonicalGermanAndIsoSpellings() {
    // The create-new parser validates a typed segment against the aliases (lower-cased) — canonical
    // English, the German exonym, and both ISO codes all land on the same country.
    assertThat(aliasResolvesTo("germany")).isEqualTo("DEU");
    assertThat(aliasResolvesTo("frankreich")).isEqualTo("FRA");
    assertThat(aliasResolvesTo("fr")).isEqualTo("FRA");
    assertThat(aliasResolvesTo("che")).isEqualTo("CHE");
    // A non-country segment (a city) resolves to nothing — that is how city is told from country.
    assertThat(
            jdbcClient
                .sql("select country_code from country_alias where alias = 'dortmund'")
                .query(String.class)
                .optional())
        .isEmpty();
  }

  @Test
  void payeeCarriesAnOptionalCityAndCountry() {
    // V4: a payee is Name + optional City + optional Country (register §3.4). Both nullable — the
    // seed and existing payees carry neither. The country FK references the seeded list.
    long payeeId =
        jdbcClient
            .sql(
                "insert into payee (name, city, country_code) "
                    + "values ('Rewe', 'Dortmund', 'DEU') returning payee_id")
            .query(Long.class)
            .single();
    assertThat(
            jdbcClient
                .sql("select city from payee where payee_id = :id")
                .param("id", payeeId)
                .query(String.class)
                .single())
        .isEqualTo("Dortmund");
  }

  private String aliasResolvesTo(String alias) {
    return jdbcClient
        .sql("select country_code from country_alias where alias = :alias")
        .param("alias", alias)
        .query(String.class)
        .single();
  }
}
