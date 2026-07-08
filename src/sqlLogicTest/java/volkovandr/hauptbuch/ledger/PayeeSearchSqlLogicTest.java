package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;

/**
 * SQL-logic tier (plan §1.5): {@link PayeeRepository#search} — the payee picker's match (register
 * §3.4). Its logic lives in the SQL: a normalised concatenation of name + city + country name
 * (lower-cased, non-alphanumerics stripped) matched as a substring, prefix matches ranked first.
 * That normalisation + ranking is exactly the SQL-resident logic this tier owns (CLAUDE.md §6), so
 * it is tested here with crafted payees rather than as a row-mapping round-trip.
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds crafted payees. {@code @Transactional} rolls each test back. Seeded names carry a
 * suite-unique {@code Qlt} suffix ("query-logic test") so that even if a container were ever
 * shared, another suite's committed payee could not collide with these assertions
 * (belt-and-suspenders beside this suite's own non-reused container).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PayeeSearchSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired PayeeRepository payeeRepository;

  private void insertPayee(String name, String city, String countryCode) {
    // Seed with raw JDBC so it rides the test's rolled-back transaction (the SQL-logic convention);
    // the query under test is the real PayeeRepository.search.
    jdbcClient
        .sql("insert into payee (name, city, country_code) values (:n, :c, :cc)")
        .param("n", name)
        .param("c", city)
        .param("cc", countryCode)
        .update();
  }

  private List<String> names(String query) {
    return payeeRepository.search(query, 20).stream().map(Payee::name).toList();
  }

  @Test
  void matchesAcrossTheNormalisedNameCityCountryConcatenation() {
    insertPayee("ReweQlt", "Dortmund", "DEU"); // normalises to reweqltdortmundgermany
    insertPayee("MigrosQlt", "Zürich", "CHE");

    // A run of characters spanning name into city matches, ignoring separators and case.
    assertThat(names("reweqltdort")).containsExactly("ReweQlt");
    // A city-only fragment matches the payee whose city contains it.
    assertThat(names("dortmund")).containsExactly("ReweQlt");
    // The country name is part of the key too.
    assertThat(names("germany")).containsExactly("ReweQlt");
  }

  @Test
  void ranksPrefixMatchBeforeAnInteriorMatch() {
    insertPayee("AldiQltSüd", null, null); // key: aldiqltsüd — 'aldiqlt' is a prefix
    insertPayee("ReweAldiQltstraße", null, null); // key: rewealdiqltstraße — 'aldiqlt' is interior

    // Both contain "aldiqlt", but the prefix match ranks first.
    assertThat(names("aldiqlt")).containsExactly("AldiQltSüd", "ReweAldiQltstraße");
  }

  @Test
  void blankQueryReturnsEveryLivePayeeAlphabetically() {
    insertPayee("ReweQlt", null, null);
    insertPayee("AldiQlt", null, null);
    insertPayee("MigrosQlt", null, null);

    // The blank-query branch returns every live payee alphabetically (Aldi < Migros < Rewe); with
    // reuse off, this suite's container holds only these three rows.
    assertThat(names("")).containsExactly("AldiQlt", "MigrosQlt", "ReweQlt");
  }

  @Test
  void payeeWithoutCityOrCountryStillMatchesOnItsName() {
    insertPayee("SpotifyQlt", null, null);

    assertThat(names("spot")).containsExactly("SpotifyQlt");
    // A fragment that would only match a (missing) city returns nothing, not a NULL-concatenation
    // false positive — coalesce keeps the absent parts out of the key.
    assertThat(names("zzz")).isEmpty();
  }

  @Test
  void softDeletedPayeesAreNotMatched() {
    insertPayee("ReweQlt", null, null);
    jdbcClient.sql("update payee set deleted_at = now() where name = 'ReweQlt'").update();

    assertThat(names("rewe")).isEmpty();
  }
}
