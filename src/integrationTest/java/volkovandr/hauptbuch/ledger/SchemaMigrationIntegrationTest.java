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
  void seedsAnFxGainLossIncomeLeafPerCurrency() {
    List<String> allCurrencies = jdbcClient.sql(ALL_CURRENCIES).query(String.class).list();
    assertThat(leafCurrenciesUnder("FX gain/loss"))
        .containsExactlyInAnyOrderElementsOf(allCurrencies);
    assertThat(leafTypeUnder("FX gain/loss")).isEqualTo("income");
  }
}
