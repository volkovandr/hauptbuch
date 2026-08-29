package volkovandr.hauptbuch.ledger;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the landing page's Balances panel (issue landing-page/01) driven
 * through {@link LandingController} against real Postgres — the panel renders pinned accounts and
 * their balances with a Total when two or more are pinned, drops the Total for a single account,
 * links each row to the account-filtered register, and is absent entirely when nothing is pinned or
 * before the base currency is set.
 *
 * <p>Accounts and postings are seeded by raw JDBC (this is a read surface). {@code @Transactional}
 * rolls each test back, including the write-once base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LandingBalancesPanelIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private long pin(String name) {
    return jdbcClient
        .sql(
            """
            insert into account (name, type, currency_code, show_on_main_page)
            values (:n, 'asset', 'EUR', true) returning account_id
            """)
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private void post(long accountId, String amount) {
    long txnId =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (:d, 'confirmed')"
                    + " returning transaction_id")
            .param("d", LocalDate.now())
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation)"
                + " values (:t, :a, :amt, 'unreconciled')")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  @Test
  void rendersRowsAndTotalWhenTwoOrMoreAccountsArePinned() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long giro = pin("Giro");
    pin("Sparkonto");
    post(giro, "1500.00");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Balances"),
                        containsString("Giro"),
                        containsString("Sparkonto"),
                        containsString("1.500,00"),
                        containsString("balances__total"),
                        containsString("/register?accountId=" + giro))));
  }

  @Test
  void omitsTheTotalRowForaSinglePinnedAccount() throws Exception {
    settingsService.setBaseCurrency("EUR");
    long giro = pin("Giro");
    post(giro, "42.00");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(allOf(containsString("Giro"), not(containsString("balances__total")))));
  }

  @Test
  void absentWhenNothingIsPinned() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(">Balances<"))));
  }

  @Test
  void absentBeforeTheBaseCurrencyIsSet() throws Exception {
    pin("Giro");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(">Balances<"))));
  }
}
