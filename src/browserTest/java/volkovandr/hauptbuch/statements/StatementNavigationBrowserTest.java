package volkovandr.hauptbuch.statements;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The statement page's previous / next shortcuts in a real browser — {@code keyboard.js}
 * (statements issue 17): "[" and "]" follow the neighbour links when the operator is not typing,
 * and do nothing while a field has focus.
 */
class StatementNavigationBrowserTest extends BrowserTest {

  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long earlier;
  private long middle;
  private long later;

  @BeforeAll
  void seed() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    long account =
        accountService
            .openAccount(
                new AccountDraft(
                    "NavBank-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    long profile =
        jdbcClient
            .sql(
                "insert into statement_profile (name, format) values ('NavBank CSV', 'csv')"
                    + " returning statement_profile_id")
            .query(Long.class)
            .single();
    earlier = statement(profile, account, "2026-02-01", "2026-02-28");
    middle = statement(profile, account, "2026-04-01", "2026-04-30");
    later = statement(profile, account, "2026-06-01", "2026-06-30");
  }

  private long statement(long profile, long account, String start, String end) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path,
               period_start, period_end)
            values (:p, :a, 'processed', 'x.csv', 'x', :s, :e)
            returning statement_id
            """)
        .param("p", profile)
        .param("a", account)
        .param("s", LocalDate.parse(start))
        .param("e", LocalDate.parse(end))
        .query(Long.class)
        .single();
  }

  private Pattern pageOf(long statementId) {
    return Pattern.compile(".*/statements/" + statementId + "(;.*)?$");
  }

  @Test
  void bracketKeysWalkToThePreviousAndNextStatement() {
    page.navigate(url("/statements/" + middle));

    page.keyboard().press("]");
    assertThat(page).hasURL(pageOf(later));
    page.keyboard().press("[");
    assertThat(page).hasURL(pageOf(middle));
    page.keyboard().press("[");
    assertThat(page).hasURL(pageOf(earlier));
  }

  @Test
  void bracketKeysDoNothingWhereThereIsNoNeighbour() {
    page.navigate(url("/statements/" + later));

    page.keyboard().press("]");

    assertThat(page).hasURL(pageOf(later));
  }

  @Test
  void bracketKeysAreTypedIntoAFieldInsteadOfNavigating() {
    page.navigate(url("/statements/" + middle));

    page.locator("#openingBalance").fill("");
    page.locator("#openingBalance").press("]");

    assertThat(page).hasURL(pageOf(middle));
  }
}
