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
 * Integration tier (plan §1.5): the register's own-account pickers against real Postgres once
 * accounts are nested (issue transaction-register-ui/25) — the dock's Account datalist and the
 * transfer targets offer posting leaves only, by their full {@code Parent - Leaf} path; a group is
 * refused inline rather than at commit; and an edit-mode pre-fill resolves back to the same
 * account.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterAccountPickerIntegrationTest {

  private static final String REGISTER_PATH = "/register";
  private static final String ENTRY_PATH = "/register/entry";
  private static final String EUR = "EUR";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String openingBalance) {
    return open(name, null, new BigDecimal(openingBalance));
  }

  private long openChild(String name, long parentId) {
    return open(name, parentId, BigDecimal.ZERO);
  }

  private long open(String name, Long parentId, BigDecimal openingBalance) {
    return accountService
        .openAccount(
            new AccountDraft(
                name, "asset", parentId, EUR, LocalDate.parse("2026-01-01"), openingBalance))
        .accountId();
  }

  private long latestTransactionId() {
    return jdbcClient
        .sql("select max(transaction_id) from transaction where deleted_at is null")
        .query(Long.class)
        .single();
  }

  @Test
  void ownAccountPickersOfferNestedAccountsByPathAndOmitTheirGroup() throws Exception {
    long bank = openAccount("BankAaa", "0");
    openChild("Credit card", bank);

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"BankAaa - Credit card (EUR)\"")))
        .andExpect(content().string(containsString("value=\"To → BankAaa - Credit card\"")))
        .andExpect(content().string(containsString("value=\"From ← BankAaa - Credit card\"")))
        // The group is not offered — posting to it would be refused (leaves-only).
        .andExpect(content().string(not(containsString("value=\"BankAaa (EUR)\""))))
        .andExpect(content().string(not(containsString("value=\"To → BankAaa\""))));
  }

  @Test
  void accountFieldRefusesGroupButResolvesItsLeafByPath() throws Exception {
    long bank = openAccount("BankAaa", "0");
    long card = openChild("Credit card", bank);

    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "BankAaa"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("is a group")))
        .andExpect(content().string(not(containsString("name=\"accountId\""))));
    mockMvc
        .perform(
            post("/register/account/resolve").param("accountText", "BankAaa - Credit card (EUR)"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"accountId\"")))
        .andExpect(content().string(containsString("value=\"" + card + "\"")));
  }

  @Test
  void accountFieldRefusesAmbiguousNameListingTheCandidates() throws Exception {
    openChild("Credit card", openAccount("BankAaa", "0"));
    long bbbCard = openChild("Credit card", openAccount("BankBbb", "0"));

    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "Credit card"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BankAaa - Credit card (EUR)")))
        .andExpect(content().string(containsString("BankBbb - Credit card (EUR)")))
        .andExpect(content().string(not(containsString("name=\"accountId\""))));
    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "BankBbb - Credit card"))
        .andExpect(content().string(containsString("value=\"" + bbbCard + "\"")));
  }

  @Test
  void transferTargetRefusesGroup() throws Exception {
    openChild("Credit card", openAccount("BankAaa", "0"));

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → BankAaa"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("is a group")))
        .andExpect(content().string(not(containsString("name=\"transferDirection\""))));
  }

  @Test
  void editingTransferToNestedAccountPrefillsLabelsThatResolveBack() throws Exception {
    long cash = openAccount("Cash", "500");
    long card = openChild("Credit card", openAccount("BankAaa", "0"));
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(card))
                .param("transferDirection", "TO")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/register/edit/" + latestTransactionId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"Cash (EUR)\"")))
        .andExpect(content().string(containsString("To → BankAaa - Credit card")));
    // The pre-filled transfer text re-resolves to the same account, so an untouched re-save
    // books the same legs.
    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → BankAaa - Credit card"))
        .andExpect(content().string(containsString("value=\"" + card + "\"")));
  }
}
