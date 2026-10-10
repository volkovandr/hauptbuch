package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the statement dock on a transfer into another currency (issue
 * statements/09) through MockMvc against real Postgres — the counterpart-amount fields the dock
 * asks for, and the cross-currency booking they produce. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementDockCrossCurrencyIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired ExchangeRateService exchangeRateService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long usdId;
  private long otherEurId;
  private long statementId;
  private long shopLine;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency("EUR");
    accountId = bank("BankAaa-EUR", "EUR");
    accountService.updateDetection(accountId, "XX00 1111 2222", false);
    usdId = bank("BankBbb-USD", "USD");
    otherEurId = bank("BankCcc-EUR", "EUR");
    exchangeRateService.recordEnteredRate(
        LocalDate.parse("2026-05-01"), "USD", new BigDecimal("100.00"), new BigDecimal("90.00"));
    long profile = StatementFixtures.saveProfile(mockMvc, jdbcClient);
    statementId = StatementFixtures.uploadAndCreate(mockMvc, profile, accountId);
    shopLine =
        jdbcClient
            .sql(
                "select statement_line_id from statement_line where statement_id = :s and amount ="
                    + " -12.50")
            .param("s", statementId)
            .query(Long.class)
            .single();
  }

  private long bank(String name, String currency) {
    return accountService
        .openAccount(
            new AccountDraft(
                name, "asset", null, currency, LocalDate.parse("2026-01-01"), BigDecimal.ZERO))
        .accountId();
  }

  private String crossUrl() {
    return "/statements/" + statementId + "/lines/" + shopLine + "/cross-currency";
  }

  @Test
  void transferIntoAnotherCurrencyAsksForTheCounterpartAmountProposedFromTheRate()
      throws Exception {
    mockMvc
        .perform(
            get(crossUrl())
                .param("categoryId", String.valueOf(usdId))
                .param("transferDirection", "TO")
                .param("date", "2026-05-02"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Counterpart amount (")))
        .andExpect(content().string(containsString("USD")))
        .andExpect(content().string(containsString("value=\"13,89\"")))
        .andExpect(content().string(not(containsString("Base amount"))));
  }

  @Test
  void transferIntoTheSameCurrencyAsksForNothing() throws Exception {
    mockMvc
        .perform(
            get(crossUrl())
                .param("categoryId", String.valueOf(otherEurId))
                .param("transferDirection", "TO")
                .param("date", "2026-05-02"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Counterpart amount"))));
  }

  @Test
  void categoryAsksForNothing() throws Exception {
    long food = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();

    mockMvc
        .perform(
            get(crossUrl()).param("categoryId", String.valueOf(food)).param("date", "2026-05-02"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Counterpart amount"))));
  }

  @Test
  void noRateOnFileLeavesTheProposalBlankRatherThanGuessing() throws Exception {
    mockMvc
        .perform(
            get(crossUrl())
                .param("categoryId", String.valueOf(usdId))
                .param("transferDirection", "TO")
                .param("date", "2026-04-01"))
        .andExpect(content().string(containsString("Counterpart amount (")))
        .andExpect(content().string(not(containsString("value=\"13,89\""))));
  }

  @Test
  void saveBooksTheTransferCrossCurrencyAndMatchesTheBankLeg() throws Exception {
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + shopLine + "/create")
                .param("date", "2026-05-02")
                .param("categoryId", String.valueOf(usdId))
                .param("transferDirection", "TO")
                .param("categoryAmount", "13,89"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("notice", "Transaction created and matched."));

    List<Map<String, Object>> legs =
        jdbcClient
            .sql(
                "select account_id, amount, base_amount, reconciliation from posting order by"
                    + " amount")
            .query()
            .listOfRows();
    assertThat(legs).hasSize(2);
    assertThat(legs.get(0).get("account_id")).isEqualTo(accountId);
    assertThat((BigDecimal) legs.get(0).get("amount")).isEqualByComparingTo("-12.50");
    assertThat((BigDecimal) legs.get(0).get("base_amount")).isEqualByComparingTo("-12.50");
    assertThat(legs.get(0).get("reconciliation")).isEqualTo("reconciled");
    assertThat(legs.get(1).get("account_id")).isEqualTo(usdId);
    assertThat((BigDecimal) legs.get(1).get("amount")).isEqualByComparingTo("13.89");
    assertThat((BigDecimal) legs.get(1).get("base_amount")).isEqualByComparingTo("12.50");
  }

  private long foodWithForeignCharge() {
    long food = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();
    jdbcClient
        .sql(
            "update statement_line set original_amount = -14.00, original_currency_code = 'USD'"
                + " where statement_line_id = :id")
        .param("id", shopLine)
        .update();
    return food;
  }

  @Test
  void categoryOnForeignChargeLineAsksForTheChargesAmount() throws Exception {
    long food = foodWithForeignCharge();

    mockMvc
        .perform(
            get(crossUrl()).param("categoryId", String.valueOf(food)).param("date", "2026-05-02"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Counterpart amount (")))
        .andExpect(content().string(containsString("value=\"14,00\"")));
  }

  @Test
  void saveBooksTheForeignPurchaseOnTheCategorysCurrencyLeafAndMatchesTheBankLeg()
      throws Exception {
    long food = foodWithForeignCharge();

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + shopLine + "/create")
                .param("date", "2026-05-02")
                .param("categoryId", String.valueOf(food))
                .param("categoryCurrencyCode", "USD")
                .param("categoryAmount", "14,00"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("notice", "Transaction created and matched."));

    List<Map<String, Object>> legs =
        jdbcClient
            .sql(
                "select p.account_id, a.currency_code, p.amount, p.base_amount, p.reconciliation"
                    + " from posting p join account a on a.account_id = p.account_id order by"
                    + " p.amount")
            .query()
            .listOfRows();
    assertThat(legs).hasSize(2);
    assertThat(legs.get(0).get("account_id")).isEqualTo(accountId);
    assertThat((BigDecimal) legs.get(0).get("amount")).isEqualByComparingTo("-12.50");
    assertThat(legs.get(0).get("reconciliation")).isEqualTo("reconciled");
    assertThat(legs.get(1).get("currency_code")).isEqualTo("USD");
    assertThat((BigDecimal) legs.get(1).get("amount")).isEqualByComparingTo("14.00");
    assertThat((BigDecimal) legs.get(1).get("base_amount")).isEqualByComparingTo("12.50");
  }

  @Test
  void saveWithoutTheCounterpartAmountReopensTheDockAndKeepsTheFields() throws Exception {
    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines/" + shopLine + "/create")
                .param("date", "2026-05-02")
                .param("categoryId", String.valueOf(usdId))
                .param("transferDirection", "TO")
                .param("categoryCurrencyCode", "USD")
                .param("categoryAmount", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("USD amount is required")))
        .andExpect(content().string(containsString("Counterpart amount (")));
  }
}
