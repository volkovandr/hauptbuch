package volkovandr.hauptbuch.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the entry dock proposes its Off account and Base amounts from the
 * Amount at the carried-forward rate (issue transaction-register-ui/27), driven through {@code
 * /register/currency-fields} against real Postgres — and keeps whatever the operator typed.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterAmountSuggestionIntegrationTest {

  private static final String CURRENCY_FIELDS_PATH = "/register/currency-fields";
  private static final String EUR = "EUR";
  private static final String DATE = "2026-02-01";
  private static final String ACCOUNT_ID = "accountId";
  private static final String CURRENCY = "categoryCurrencyCode";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String currencyCode) {
    return accountService
        .openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                currencyCode,
                LocalDate.parse("2026-01-01"),
                new BigDecimal("500")))
        .accountId();
  }

  private void rate(String currencyCode, String rate) {
    jdbcClient
        .sql(
            "insert into exchange_rate (currency_code, date, rate, source)"
                + " values (:c, '2026-01-01', :r, 'manual')")
        .param("c", currencyCode)
        .param("r", new BigDecimal(rate))
        .update();
  }

  @Test
  void typingTheAmountProposesOffAccountAtTheCarriedForwardRate() throws Exception {
    long cash = openAccount("Cash", EUR);
    rate("USD", "0.90");

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param(CURRENCY, "USD")
                .param("amount", "10"))
        .andExpect(status().isOk())
        // 10 USD at the January rate (0.90) → 9,00 EUR off the account, remembered as a proposal.
        .andExpect(content().string(containsString("value=\"9,00\"")))
        .andExpect(
            content().string(containsString("name=\"offAccountSuggestion\" value=\"9,00\"")));
  }

  @Test
  void changedAmountReproposesAnUntouchedOffAccount() throws Exception {
    long cash = openAccount("Cash", EUR);
    rate("USD", "0.90");

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param(CURRENCY, "USD")
                .param("amount", "20")
                .param("offAccountAmount", "9,00")
                .param("offAccountSuggestion", "9,00"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"18,00\"")));
  }

  @Test
  void typedOffAccountIsNeverOverwritten() throws Exception {
    long cash = openAccount("Cash", EUR);
    rate("USD", "0.90");

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param(CURRENCY, "USD")
                .param("amount", "20")
                .param("offAccountAmount", "17,80")
                .param("offAccountSuggestion", "9,00"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"17,80\"")))
        .andExpect(content().string(not(containsString("value=\"18,00\""))));
  }

  @Test
  void neitherCurrencyBaseProposesOffAccountThroughBaseAndBaseFromTheAmount() throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF");
    rate("USD", "0.90");
    rate("CHF", "0.95");

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(chfCard))
                .param(CURRENCY, "USD")
                .param("amount", "10"))
        .andExpect(status().isOk())
        // 10 USD → 9,00 EUR → 9,00 / 0,95 = 9,47 CHF off the account; Base 9,00 EUR.
        .andExpect(content().string(containsString("value=\"9,47\"")))
        .andExpect(content().string(containsString("name=\"baseSuggestion\" value=\"9,00\"")));
  }

  @Test
  void editedTransactionKeepsItsLoadedAmountsWhenTheAmountChanges() throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF");
    rate("USD", "0.90");
    rate("CHF", "0.95");

    // An edit dock starts with no suggestions, so the loaded Off account and frozen Base are the
    // operator's facts: changing the Amount must not re-propose over them.
    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(chfCard))
                .param(CURRENCY, "USD")
                .param("amount", "20")
                .param("offAccountAmount", "9,50")
                .param("baseAmount", "9,10"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"9,50\"")))
        .andExpect(content().string(containsString("value=\"9,10\"")))
        .andExpect(content().string(not(containsString("value=\"18,00\""))))
        .andExpect(content().string(not(containsString("value=\"18,95\""))));
  }

  @Test
  void noRateOnFileLeavesOffAccountBlank() throws Exception {
    long cash = openAccount("Cash", EUR);

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param(CURRENCY, "USD")
                .param("amount", "10"))
        .andExpect(status().isOk())
        // The field is shown, but nothing is guessed into it.
        .andExpect(content().string(containsString("name=\"offAccountAmount\"")))
        .andExpect(content().string(not(containsString("value=\"9,"))));
  }

  @Test
  void amountAndDateChangesRefreshTheProposals() throws Exception {
    long cash = openAccount("Cash", EUR);

    mockMvc
        .perform(get("/register").param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // Both inputs post the form to the currency-fields refresh, and drop it on submit.
        .andExpect(content().string(containsString("hx-sync=\"closest form:abort\"")));
  }
}
