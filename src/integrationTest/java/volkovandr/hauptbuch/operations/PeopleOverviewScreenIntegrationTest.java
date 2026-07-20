package volkovandr.hauptbuch.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the stage-8d People-balances screen driven through {@link
 * PeopleOverviewController} against real Postgres — the acceptance surface for "read a person's
 * position". It proves the signed per-currency figures, the supplementary base-currency gloss
 * (including a cross-currency total valued at today's rate), the directional wording behind the
 * expander, the register pre-filter link, and the settled state.
 *
 * <p>Person leaves are provisioned through {@code debts}; a lone posting to a leaf is seeded by raw
 * JDBC to give it a balance without going through the entry engine (stage 8b owns that path). Each
 * test is rolled back, including the write-once base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PeopleOverviewScreenIntegrationTest {

  private static final String PEOPLE_PATH = "/people";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";

  @Autowired MockMvc mockMvc;
  @Autowired SettingsService settingsService;
  @Autowired PersonProvisioningService personProvisioningService;
  @Autowired PersonService personService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long provisionLeaf(String name, String currency) {
    return personProvisioningService.ensureLeaf(name, currency, false).accountId();
  }

  private void seedPosting(long accountId, String amount) {
    long transactionId =
        jdbcClient
            .sql("insert into transaction (date) values (current_date) returning transaction_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private void seedRate(String currency, String rate) {
    jdbcClient
        .sql(
            "insert into exchange_rate (currency_code, date, rate, source)"
                + " values (:c, current_date, :r, 'manual')")
        .param("c", currency)
        .param("r", new BigDecimal(rate))
        .update();
  }

  @Test
  void baseCurrencyOnlyPersonShowsFigureDirectionAndRegisterLinkButNoGloss() throws Exception {
    long benLeaf = provisionLeaf("Ben", EUR);
    seedPosting(benLeaf, "25.00");

    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Ben")))
        .andExpect(content().string(containsString("25,00")))
        .andExpect(content().string(containsString("Ben owes you 25,00"))) // expanded wording
        .andExpect(content().string(containsString("View in register")))
        .andExpect(content().string(containsString("accountId=" + benLeaf)))
        // A wholly-base position shows no base-currency gloss - it would just restate the figure.
        // Base figures render bare, so the code "EUR" appears only inside a gloss: its absence
        // proves the gloss is suppressed.
        .andExpect(content().string(not(containsString("EUR"))));
  }

  @Test
  void personYouOweShowsNegativeFigureAndDirectionalWording() throws Exception {
    long caraLeaf = provisionLeaf("Cara", EUR);
    seedPosting(caraLeaf, "-10.00");

    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(content().string(containsString("-10,00")))
        .andExpect(content().string(containsString("You owe 10,00")));
  }

  @Test
  void crossCurrencyPersonValuesBaseTotalAtTodaysRate() throws Exception {
    long maxEur = provisionLeaf("Max", EUR);
    long maxChf = provisionLeaf("Max", CHF);
    seedPosting(maxEur, "-10.00");
    seedPosting(maxChf, "5.00");
    seedRate(CHF, "0.90"); // 5 CHF = 4,50 base → total −10 + 4,50 = −5,50

    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(content().string(containsString("5,00 CHF")))
        .andExpect(content().string(containsString("-5,50"))) // the base total
        .andExpect(
            content().string(containsString("EUR"))) // named in the base currency, not "base"
        .andExpect(content().string(containsString("accountId=" + maxEur)))
        .andExpect(content().string(containsString("accountId=" + maxChf)));
  }

  @Test
  void settledPersonShowsSettled() throws Exception {
    personService.create("Anna"); // no leaves → settled, no register link

    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(content().string(containsString("Anna")))
        .andExpect(content().string(containsString("Settled")));
  }

  @Test
  void emptyRosterOffersTheCreateFormAndTheEmptyNote() throws Exception {
    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add a person")))
        .andExpect(content().string(containsString("No people yet.")));
  }
}
