package volkovandr.hauptbuch.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the entry dock's currency pre-selection from the payee (issue
 * transaction-register-ui/17) driven through its controllers against real Postgres — a payee change
 * re-derives the currency default from the last transaction that payee had on the account.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterPayeeCurrencyIntegrationTest {

  private static final String REGISTER_PATH = "/register";
  private static final String ENTRY_PATH = "/register/entry";
  private static final String CURRENCY_FIELDS_PATH = "/register/currency-fields";
  private static final String EUR = "EUR";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String openingBalance) {
    return accountService
        .openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                EUR,
                LocalDate.parse("2026-01-01"),
                new BigDecimal(openingBalance)))
        .accountId();
  }

  private long insertCategory(String name) {
    return accountService.insertLeaf(name, "expense", null, EUR).accountId();
  }

  /** Thymeleaf renders the option over several lines: {@code value="CHF"\n selected="selected"}. */
  private static String selectedOption(String code) {
    return "(?s).*value=\"" + code + "\"\\s+selected=\"selected\".*";
  }

  @Test
  void payeeChangePreselectsTheCurrencyLastUsedWithThatPayeeOnThatAccount() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    // A EUR account once paid ShopAaa a CHF bill (issue transaction-register-ui/17).
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("payeeText", "ShopAaa")
                .param("amount", "9,10")
                .param("categoryId", String.valueOf(food))
                .param("categoryCurrencyCode", "CHF")
                .param("categoryAmount", "10")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());

    // The payee-change refresh posts without the selector, so the default is re-derived: CHF is
    // pre-selected and its amount field revealed.
    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", "2026-03-01")
                .param("accountId", String.valueOf(cash))
                .param("payeeText", "ShopAaa"))
        .andExpect(status().isOk())
        .andExpect(content().string(matchesRegex(selectedOption("CHF"))))
        .andExpect(content().string(containsString("name=\"categoryAmount\"")));
  }

  @Test
  void payeeWithoutHistoryOnTheAccountKeepsTheAccountCurrency() throws Exception {
    long cash = openAccount("Cash", "500");

    mockMvc
        .perform(
            post(CURRENCY_FIELDS_PATH)
                .param("date", "2026-03-01")
                .param("accountId", String.valueOf(cash))
                .param("payeeText", "ShopBbb"))
        .andExpect(status().isOk())
        .andExpect(content().string(matchesRegex(selectedOption("EUR"))))
        .andExpect(content().string(not(containsString("name=\"categoryAmount\""))));
  }

  @Test
  void dockRefreshesTheCurrencyFieldsWhenThePayeeChanges() throws Exception {
    long cash = openAccount("Cash", "500");

    mockMvc
        .perform(get(REGISTER_PATH).param("accountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("hx-trigger=\"change from:#entry-payee\"")));
  }
}
