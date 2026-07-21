package volkovandr.hauptbuch.operations;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the stage-8e settle-up launcher driven through {@link
 * SettleUpController} against real Postgres — the acceptance surface for "settle a person's debt".
 * It proves the form renders the pre-scoped account picker, direction wording and amount default;
 * that a same-currency settle commits through the engine and zeroes the leaf (the People page then
 * reads settled); that a cross-currency funding account reveals the second amount field (register
 * §3.8a); and that a cross-currency settle balances and zeroes the leaf too.
 *
 * <p>A person leaf is provisioned through {@code debts}; a lone unbalanced posting seeds its
 * balance by raw JDBC (the entry engine's own path is stage 8b's), and the funding account is a
 * real opened account. Each test is rolled back, including the write-once base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SettleUpScreenIntegrationTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String TODAY = "2026-07-21";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired PersonProvisioningService personProvisioningService;
  @Autowired PersonService personService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String currency) {
    Account account =
        accountService.openAccount(
            new AccountDraft(
                name, "asset", null, currency, LocalDate.parse("2026-01-01"), BigDecimal.ZERO));
    return account.accountId();
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

  private String settlePath(String name) {
    return "/people/" + personId(name) + "/settle";
  }

  @Test
  void formRendersTheAccountPickerDirectionAndDefaultedAmount() throws Exception {
    openAccount("Cash", EUR);
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // Max owes you 10 EUR

    mockMvc
        .perform(get(settlePath("Max")).param("currency", EUR))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settle up with Max")))
        .andExpect(content().string(containsString("Max owes you 10,00")))
        .andExpect(content().string(containsString("Cash (EUR)")))
        .andExpect(content().string(containsString("name=\"amount\"")))
        .andExpect(content().string(containsString("10,00"))) // amount defaulted to the outstanding
        .andExpect(content().string(containsString("Record settlement")));
  }

  @Test
  void settlingSameCurrencyDebtZeroesTheLeaf() throws Exception {
    long cash = openAccount("Cash", EUR);
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // Max owes you 10 EUR

    mockMvc
        .perform(
            post(settlePath("Max"))
                .param("currency", EUR)
                .param("accountId", String.valueOf(cash))
                .param("date", TODAY)
                .param("amount", "10,00"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/people"));

    // The position now reads settled on the People page.
    mockMvc.perform(get("/people")).andExpect(content().string(containsString("Settled")));
  }

  @Test
  void differentCurrencyFundingAccountRevealsTheSettlesField() throws Exception {
    openAccount("Cash", EUR); // only a EUR account, so settling a CHF debt is cross-currency
    seedPosting(provisionLeaf("Max", CHF), "10.00");

    mockMvc
        .perform(get(settlePath("Max")).param("currency", CHF))
        .andExpect(status().isOk())
        // Two fields (register §3.8a): what you receive in EUR, and the CHF debt it settles.
        .andExpect(content().string(containsString("Amount (EUR)")))
        .andExpect(content().string(containsString("Settles (CHF)")))
        .andExpect(content().string(containsString("name=\"categoryAmount\"")));
  }

  @Test
  void amountFieldsRecomputeReturnsJustTheAmountsBlock() throws Exception {
    long cash = openAccount("Cash", EUR);
    seedPosting(provisionLeaf("Max", CHF), "10.00");

    mockMvc
        .perform(
            post(settlePath("Max") + "/amount-fields")
                .param("currency", CHF)
                .param("accountId", String.valueOf(cash))
                .param("date", TODAY))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"settle-amounts\"")))
        .andExpect(content().string(containsString("Settles (CHF)")));
  }

  @Test
  void settlingCrossCurrencyDebtBalancesInBaseAndZeroesTheLeaf() throws Exception {
    long cash = openAccount("Cash", EUR);
    seedPosting(provisionLeaf("Max", CHF), "10.00"); // Max owes you 10 CHF, base EUR

    mockMvc
        .perform(
            post(settlePath("Max"))
                .param("currency", CHF)
                .param("accountId", String.valueOf(cash))
                .param("date", TODAY)
                .param("amount", "9,20") // received into the EUR account (base leg)
                .param("categoryAmount", "10,00")) // clears the 10 CHF debt
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/people"));

    mockMvc.perform(get("/people")).andExpect(content().string(containsString("Settled")));
  }

  @Test
  void rejectedCommitReRendersTheFormWithTheMessage() throws Exception {
    seedPosting(provisionLeaf("Max", EUR), "10.00"); // no funding account picked

    mockMvc
        .perform(
            post(settlePath("Max"))
                .param("currency", EUR)
                .param("date", TODAY)
                .param("amount", "10,00"))
        .andExpect(status().isOk()) // re-rendered, not redirected
        .andExpect(header().doesNotExist("Location"))
        .andExpect(content().string(containsString("Settle up with Max")));
  }
}
