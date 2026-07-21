package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.SettleTarget;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the stage-8f person merge driven through {@link
 * PersonMergeController} against real Postgres — the acceptance surface for "remove a person who is
 * owed or owes money by folding them into another". It proves the form renders the source's
 * positions and the target picker; that a merge moves the source's postings onto the target's
 * matching-currency leaf (provisioning it when absent), across every currency, and retires the
 * source; and that a self-merge re-renders the form with the message rather than 500-ing.
 *
 * <p>Leaves are provisioned through {@code debts} and a lone unbalanced posting seeds each leaf's
 * balance by raw JDBC (the entry engine's own path is stage 8b's). Each test is rolled back,
 * including the write-once base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PersonMergeScreenIntegrationTest {

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

  private long personId(String name) {
    return ((PersonMatch.Live) personService.matchExact(name)).person().personId();
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

  private String mergePath(String name) {
    return "/people/" + personId(name) + "/merge";
  }

  /** The person's summed native balance in one currency, or zero when they hold no such leaf. */
  private BigDecimal balanceOf(long personId, String currency) {
    return personService
        .settleTarget(personId, currency)
        .map(SettleTarget::signedBalance)
        .orElse(BigDecimal.ZERO);
  }

  @Test
  void formRendersTheSourcesPositionsAndTheTargetPicker() throws Exception {
    seedPosting(provisionLeaf("Max", CHF), "-10.00"); // you owe Max 10 CHF
    provisionLeaf("Alex", EUR);

    mockMvc
        .perform(get(mergePath("Max")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Merge Max")))
        .andExpect(content().string(containsString("You owe Max 10,00")))
        .andExpect(content().string(containsString("Alex")))
        .andExpect(content().string(containsString("Merge and remove")));
  }

  @Test
  void mergeFoldsTheSourcesPostingsOntoTheTargetAndRemovesTheSource() throws Exception {
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // Max owes you 10 EUR
    provisionLeaf("Alex", EUR);
    long maxId = personId("Max");
    long alexId = personId("Alex");

    mockMvc
        .perform(post(mergePath("Max")).param("targetPersonId", String.valueOf(alexId)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/people"));

    // Max is retired; Alex now carries the folded 10 EUR position.
    assertThat(personService.findById(maxId)).isEmpty();
    assertThat(balanceOf(alexId, EUR)).isEqualByComparingTo("10.00");
  }

  @Test
  void mergeFoldsEveryCurrencyProvisioningTargetLeavesAsNeeded() throws Exception {
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // Max owes you 10 EUR
    seedPosting(provisionLeaf("Max", CHF), "-4.00"); // you owe Max 4 CHF
    provisionLeaf("Alex", EUR); // Alex has a EUR leaf but no CHF one yet
    long alexId = personId("Alex");

    mockMvc
        .perform(post(mergePath("Max")).param("targetPersonId", String.valueOf(alexId)))
        .andExpect(status().is3xxRedirection());

    assertThat(balanceOf(alexId, EUR)).isEqualByComparingTo("10.00");
    assertThat(balanceOf(alexId, CHF)).isEqualByComparingTo("-4.00");
  }

  @Test
  void mergeSumsOntoTargetAlreadyHoldingSameCurrencyBalance() throws Exception {
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // Max owes you 10 EUR
    seedPosting(provisionLeaf("Alex", EUR), "-3.00"); // you already owe Alex 3 EUR
    long alexId = personId("Alex");

    mockMvc
        .perform(post(mergePath("Max")).param("targetPersonId", String.valueOf(alexId)))
        .andExpect(status().is3xxRedirection());

    // Reassignment is additive: Alex's own -3 and Max's folded +10 net to +7, not either alone.
    assertThat(balanceOf(alexId, EUR)).isEqualByComparingTo("7.00");
  }

  @Test
  void mergingIntoThemselvesReRendersTheFormWithTheMessage() throws Exception {
    seedPosting(provisionLeaf("Max", EUR), "10.00");
    long maxId = personId("Max");

    mockMvc
        .perform(post(mergePath("Max")).param("targetPersonId", String.valueOf(maxId)))
        .andExpect(status().isOk()) // re-rendered, not redirected
        .andExpect(header().doesNotExist("Location"))
        .andExpect(content().string(containsString("Merge Max")))
        .andExpect(content().string(containsString("themselves")));

    // The source is untouched — still live, still holding its balance.
    assertThat(personService.findById(maxId)).isPresent();
  }
}
